package tijos.framework.sensor.bc28;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.util.Date;

import tijos.framework.devicecenter.TiUART;
import tijos.framework.util.Delay;
import tijos.framework.util.Formatter;

/**
 * Quectel BC95/BC28 NB-IOT module driver for TiJOS
 * NB-IOT module need to bind a server IP for data transportation, please
 * confirm with the NB-IOT provider before testing
 */
public class TiBC28 extends Thread {

    // IO stream for UART
    InputStream input;
    OutputStream output;

    TiUART uart;

    // Keep the UART read thread running
    private boolean keeprunning = true;

    private ATResponse atResp = new ATResponse();

    private IDeviceEventListener eventListener;

    /**
     * Initialize IO stream for UART
     *
     * @param uart TiUART object
     */
    public TiBC28(TiUART uart) {
        this.uart = uart;
        this.input = new BufferedInputStream(new TiUartInputStream(uart), 256);
        this.output = new TiUartOutputStream(uart);

        this.setDaemon(true);
        this.start();
    }

    @Override
    public void run() {
		boolean udpData = false;
        while (keeprunning) {

            try {
                String resp = readLine();
                if (resp.length() == 0) {
                    continue;
                }

                System.out.println(resp);

                if (resp.equals("OK") || resp.endsWith("ERROR")) {
                    synchronized (this.atResp) {
                        this.atResp.notifyAll();
                    }
                    continue;
                }

				if (resp.contains("+NNMI")) // new coap message arrived
				{
					synchronized (this.atResp) {
						this.coapReceive(resp);
					}
					
				} else if (resp.contains("+NSONMI")) // UDP
				{
					this.udpReceive(resp);
					udpData = true;
					
				} else if(resp.contains("+NSMI"))// response for the request
				{
					//ignore 
				}
				else if(udpData) {
					udpData = false;
					this.udpDataParse(resp);
				}
				else
				{
					this.atResp.setResponse(resp);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}

        }
    }

    /**
     * Event listener for data arrived from remote node
     *
     * @param listener
     */
    public void setEventListener(IDeviceEventListener listener) {
        this.eventListener = listener;
    }

    /**
     * 查询模块射频功能状态
     *
     * @return true 射频已打开 false 射频未打开
     * @throws IOException
     */
    public boolean isMTOn() throws IOException {

        String resp = sendCommand("AT+CFUN?");
        if (resp.equals("+CFUN:1"))
            return true;

        return false;
    }

    /**
     * 打开模块射频功能
     *
     * @throws IOException
     */
    public void turnOnMT() throws IOException {
        sendCommand("AT+CFUN=1");
    }

    /**
     * 关闭模块射频功能
     *
     * @throws IOException
     */
    public void turnOffMT() throws IOException {
        sendCommand("AT+CFUN=0");
    }

    /**
     * 查询 IMSI 码 IMSI 是国际移动用户识别码，International Mobile Subscriber Identification
     * Number 的缩写
     *
     * @return IMSI
     * @throws IOException
     */
    public String getIMSI() throws IOException {
        String resp = sendCommand("AT+CIMI");
        if (resp == null) {
            throw new IOException("Wrong response");
        }

        return resp;
    }

    /**
     * 查询模块 IMEI, 国际移动设备识别码International Mobile Equipment Identity
     *
     * @return
     * @throws IOException
     */
    public String getIMEI() throws IOException {
        String resp = sendCommand("AT+CGSN=1");

        int end = resp.lastIndexOf(':');
        if (end < 0) {
            throw new IOException("Wrong response");
        }

        String imei = resp.substring(end + 1);
        return imei;
    }

    /**
     * 查询模块信号
     *
     * @return 0 - 表示网络未知，或者网络未附着
     * @throws IOException
     */
    public int getRSSI() throws IOException {
        String resp = sendCommand("AT+CSQ");

		String [] res = resp.split("\n");
		resp = res[res.length - 1];
		
		int begin = resp.indexOf(':');
		int end = resp.lastIndexOf(',');

        if (begin < 0 || end < 0 || begin >= end)
            throw new IOException("Wrong response");

        String rssi = resp.substring(begin + 1, end);

        int r = Integer.parseInt(rssi);
        if (r == 99) {// no signl
            r = 0;
        }

        return r;
    }

    /**
     * 查询网络是否激活
     *
     * @return
     * @throws IOException
     */
    public boolean isNetworkActived() throws IOException {
        String resp = sendCommand("AT+CGATT?");
        if (resp.equals("+CGATT:1"))
            return true;
        return false;

    }

    /**
     * 激活网络
     *
     * @throws IOException
     */
    public void activeNetwork() throws IOException {
        sendCommand("AT+CGATT=1");
    }

    /**
     * 查询网络是否注册
     *
     * @return
     * @throws IOException
     */
    public boolean isNetworkRegistred() throws IOException {
        String resp = sendCommand("AT+CEREG?");

        int begin = resp.lastIndexOf(',');
        if (begin < 0)
            throw new IOException("Wrong response");

        String stat = resp.substring(begin + 1);

        int s = Integer.parseInt(stat);
        return s > 0 ? true : false;
    }

    /**
     * 查询当前网络连接状态
     *
     * @return 0 处于 IDLE 状态 1 处于已连接状态 当处于 IDLE 状态时，只要发送数据，就会变成已连接状态
     * @throws IOException
     */
    public int getNetworkStatus() throws IOException {
        String resp = sendCommand("AT+CSCON?");

        int begin = resp.lastIndexOf(',');
        if (begin < 0)
            throw new IOException("Wrong response");

        String stat = resp.substring(begin + 1);

        return Integer.parseInt(stat);
    }

    /**
     * 获取设备IP地址
     *
     * @return
     * @throws IOException
     */
    public String getIPAddress() throws IOException {
        String resp = sendCommand("AT+CGPADDR=0");

		String [] res = resp.split("\n");
		resp = res[res.length - 1];
		
		if (!resp.startsWith("+CGPADDR"))
			throw new IOException("Failed to get IP address");

        int pos = resp.lastIndexOf(',');
        if (pos < 0)
            return "";

        return resp.substring(pos + 1);
    }

    /**
     * 设置自动入网
     *
     * @param auto true - 重启后自动入网, false - 模块重启后不会自动连接到网络
     * @throws IOException
     */
    public void configAutoConnect(boolean auto) throws IOException {
        if (auto)
            sendCommand("AT+NCONFIG=AUTOCONNECT,TRUE");
        else
            sendCommand("AT+NCONFIG=AUTOCONNECT,FALSE");

    }

    /**
     * 查询模组状态
     *
     * @return
     * @throws IOException
     */
    public String[] queryUEStatistics() throws IOException {

        String resp = sendCommand("AT+NUESTATS");

        return resp.split("\n");

    }

    /**
     * 测试 IP 地址是否可用
     *
     * @param ip
     * @return
     * @throws IOException
     */
    public boolean ping(String ip) throws IOException {

        String result = sendCommand("AT+NPING=" + ip);
        if (result.startsWith("+NPING:"))
            return true;

        return false;
    }

    /**
     * 创建 UDP 通信 Socket
     *
     * @param listenPort 本地监听端口
     * @return socket id
     * @throws IOException
     */
    public int createUDPSocket(int listenPort) throws IOException {
        String resp = sendCommand("AT+NSOCR=DGRAM,17," + listenPort + ",1");

        return Integer.parseInt(resp);
    }

    /**
     * 关闭socket
     *
     * @param socketId
     * @throws IOException
     */
    public void closeUDPSocket(int socketId) throws IOException {
        sendCommand("AT+NSOCL=" + socketId);
    }

    /**
     * 发送UDP数据包到远程服务器
     *
     * @param socketId
     * @param remoteAddr 远程服务器IP
     * @param remotePort 远程服务器 端口
     * @param data       将发送的数据
     * @return 成送数据长度
     * @throws IOException
     */
    public int udpSend(int socketId, String remoteAddr, int remotePort, byte[] data) throws IOException {
        String resp = sendCommand("AT+NSOST=" + socketId + "," + remoteAddr + "," + remotePort + "," + data.length + ","
                + Formatter.toHexString(data));

        if (socketId != resp.charAt(0) - '0')
            throw new IOException("Wrong socket id");

        return Integer.parseInt(resp.substring(2));
    }

    /**
     * 接收UDP数据 注意： 由于NB-IOT及UDP的特点， 下行数据需要要收到上行数据后立刻下发, 同时不保证数据能够到达, 在实际 应用中需要根据实际
     * 情况进行处理
     *
     * @return 收到的UDP数据
     * @throws IOException
     */
    public void udpReceive(String resp) throws IOException {

        int begin = resp.indexOf(':');
        int end = resp.lastIndexOf(',');

        if (begin < 0 || end < 0 || begin > end)
            throw new IOException("Wrong response");

        int socketId = Integer.parseInt(resp.substring(begin + 1, end));
        int length = Integer.parseInt(resp.substring(end + 1));

		String readUdpDataCmd = "AT+NSORF=" + socketId + "," + length + "\r\n";
		output.write(readUdpDataCmd.getBytes());
	
	}

	public void udpDataParse(String resp) throws IOException {
		
		int begin = resp.indexOf(',');
		int socketId = Integer.parseInt(resp.substring(0, begin));

		int ipPos = resp.indexOf(',', begin + 1);
		String remoteAddr = resp.substring(begin + 1, ipPos);

		int portPos = resp.indexOf(',', ipPos + 1);
		int port = Integer.parseInt(resp.substring(ipPos + 1, portPos));

		int lenPos = resp.indexOf(',', portPos + 1);
		int length = Integer.parseInt(resp.substring(portPos + 1, lenPos));

		int dataPos = resp.indexOf(',', lenPos + 1);
		String data = resp.substring(lenPos + 1, dataPos);

		int left = Integer.parseInt(resp.substring(dataPos + 1));

		byte[] packet = this.hexStringToByte(data);
		if (this.eventListener != null)
			this.eventListener.onUDPDataArrived(packet);
	}
	
	/**
	 * 设备COAP/CDP 服务器IP及端口
	 * 
	 * @param ip
	 * @param port
	 * @throws IOException
	 */
	public void setCDPServer(String ip, int port) throws IOException {

		sendCommand("AT+NCDP=" + ip + "," + port);
		
		sendCommand("AT+NCDP?");
	}

    /**
     * 启用发送和新消息通知
     *
     * @param enable true - 开启 false- 关闭
     * @throws IOException
     */
    public void enableMsgNotification(boolean enable) throws IOException {
        if (enable) {
            sendCommand("AT+NSMI=1");
        } else {
            sendCommand("AT+NSMI=0");
        }
    }

    /**
     * 开启新消息通知，配置后，若模块接收到 CoAP 消息，会主动向串口发送响应
     *
     * @throws IOException
     */
    public void enableNewArriveMessage() throws IOException {
        sendCommand("AT+NNMI=1");
    }

    /**
     * 通过COAP向服务器发送数据
     *
     * @param data 待发送数据
     * @throws IOException
     */
    public void coapSend(byte[] data) throws IOException {

        String result = sendCommand("AT+NMGS=" + data.length + "," + Formatter.toHexString(data));


        result = sendCommand("AT+NQMGS");
        if (!result.contains("ERROR=0"))
            throw new IOException("Failed to send coap message");
    }

    public void coapSend(byte[] data, int off, int len) throws IOException {

        String result = sendCommand("AT+NMGS=" + len + "," + Formatter.toHexString(data, off, len, ""));

        result = sendCommand("AT+NQMGS");
        if (!result.contains("ERROR=0"))
            throw new IOException("Failed to send coap message");
    }


    /**
     * 接收COAP数据 注意： 由于NB-IOT的特点， 下行数据需要要收到上行数据后立刻下发, 同时不保证数据能够到达, 在实际 应用中需要根据实际
     * 情况进行处理
     *
     * @return
     * @throws IOException
     */
    public void coapReceive(String data) throws IOException {

        if (data.length() == 0)
            return;

        int pos = data.lastIndexOf(',');
        if (pos > 0) {
            byte[] buff = this.hexStringToByte(data.substring(pos + 1));
            if (this.eventListener != null)
                this.eventListener.onCoapDataArrived(buff);
        }

    }

	/**
	 * Get date time from the network
	 * 
	 * @return
	 * @throws IOException
	 */
	@SuppressWarnings("deprecation")
	public Date getDateTime() throws IOException {
		String data = sendCommand("AT+CCLK?");
		
		if(data.length() < 10) 
			return null;
		
		int begin = data.indexOf(':');

        int yearPos = data.indexOf('/', begin + 1);
        int year = Integer.parseInt(data.substring(begin + 1, yearPos));

        int monPos = data.indexOf('/', yearPos + 1);
        int month = Integer.parseInt(data.substring(yearPos + 1, monPos));

        int dayPos = data.indexOf(',', yearPos + 1);
        int day = Integer.parseInt(data.substring(monPos + 1, dayPos));

        int hourPos = data.indexOf(':', dayPos + 1);
        int hours = Integer.parseInt(data.substring(dayPos + 1, hourPos));

        int minPos = data.indexOf(':', hourPos + 1);
        int minutes = Integer.parseInt(data.substring(hourPos + 1, minPos));

        int secondPos = data.indexOf('+', minPos + 1);
        int seconds = Integer.parseInt(data.substring(minPos + 1, secondPos));

        return new Date(year + 100, month - 1, day, hours, minutes, seconds);

    }

    /**
     * Send AT command to device
     *
     * @param cmd
     * @throws IOException
     */
    private String sendCommand(String cmd) throws IOException {

        synchronized (this.atResp) {
            try {
                this.atResp.reset();
                output.write((cmd + "\r\n").getBytes());

                this.atResp.wait(5000);
                // this.atResp.wait();

                return atResp.getResponse();

            } catch (Exception ie) {

            }
        }
        return null;
    }

    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder(32);

        int timeout = 4000;
        while ((timeout -= 50) > 0) {
            if (input.available() < 2) {
                Delay.msDelay(50);
                continue;
            }

            int val = input.read();
            if (val == 0x0D) {
                val = input.read(); // 0x0a
                break;
            }

            sb.append((char) val);
        }
        return sb.toString();
    }

    private void clearInput() throws IOException {

        while (this.input.read() > 0)
            ;
        this.uart.clear(3); // clear both input and output buffer
    }

    private byte[] hexStringToByte(String str) {
        if (str == null) {
            return null;
        }
        if (str.length() == 0) {
            return new byte[0];
        }
        byte[] byteArray = new byte[str.length() / 2];
        for (int i = 0; i < byteArray.length; i++) {
            String subStr = str.substring(2 * i, 2 * i + 2);
            byteArray[i] = ((byte) Integer.parseInt(subStr, 16));
        }
        return byteArray;
    }

}

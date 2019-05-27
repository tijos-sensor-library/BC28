package tijos.framework.sensor.bc28;

import java.io.IOException;
import java.io.InputStream;

import tijos.framework.devicecenter.TiUART;


/**
 * Input stream for UART, it's recommended to work with BufferedIOStream
 *
 * @author lemon
 */
public class TiUartInputStream extends InputStream {

    TiUART uart = null;

    public TiUartInputStream(TiUART uart) {
        this.uart = uart;
    }

    @Override
    public int available() throws IOException {
        return this.uart.available();
    }

    public int read(byte[] b, int off, int len) throws IOException {
        int ret = this.uart.read(b, off, len);
		/*if(ret > 0) {
			System.out.println("Read " + Formatter.toHexString(b, off, ret, " "));
		}
			*/
        return ret;
    }

    @Override
    public int read() throws IOException {
        byte[] temp = new byte[1];
        if (this.uart.read(temp, 0, 1) > 0)
            return temp[0] & 0xFF;

        return -1; //EOF
    }


}

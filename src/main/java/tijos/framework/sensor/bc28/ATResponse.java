package tijos.framework.sensor.bc28;

public class ATResponse {
	
	private String response;
	
	public void reset() {
		this.response = "";
	}
	
	public void setResponse(String resp) 
	{
		if(this.response.length() > 0)
			this.response += "\n";
		
		this.response += resp;
	}
	
	public String getResponse() {
		return this.response;
	}
	
}

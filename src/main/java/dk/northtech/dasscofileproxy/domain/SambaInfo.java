package dk.northtech.dasscofileproxy.domain;

public class SambaInfo {
    public Integer port;
    public String hostname;
    public String smb_name;
    public String token;
    public SambaRequestStatus sambaRequestStatus;
    public String sambaRequestStatusMessage;

    public SambaInfo(Integer port, String hostname, String smb_name, String token, SambaRequestStatus sambaRequestStatus, String sambaRequestStatusMessage) {
        this.port = port;
        this.hostname = hostname;
        this.smb_name = smb_name;
        this.token = token;
        this.sambaRequestStatus = sambaRequestStatus;
        this.sambaRequestStatusMessage = sambaRequestStatusMessage;
    }
}

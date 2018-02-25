package com.armchairoctopus.s3ftps;

public class Request {
    String operation;
    String host;
    String user;
    String password;
    String uploadPath;
    String downloadPath;
    String sentPath;
    String bucket;
    
    public String getOperation() {
        return operation;
    }
    public void setOperation(String operation) {
        this.operation = operation;
    }
    public String getHost() {
        return host;
    }
    public void setHost(String host) {
        this.host = host;
    }
    public String getUser() {
        return user;
    }
    public void setUser(String user) {
        this.user = user;
    }
    public String getPassword() {
        return password;
    }
    public void setPassword(String password) {
        this.password = password;
    }
    public String getUploadPath() {
        return uploadPath;
    }
    public void setUploadPath(String uploadPath) {
        this.uploadPath = uploadPath;
    }
    public String getDownloadPath() {
        return downloadPath;
    }
    public void setDownloadPath(String downloadPath) {
        this.downloadPath = downloadPath;
    }
    public String getSentPath() {
        return sentPath;
    }
    public void setSentPath(String sentPath) {
        this.sentPath = sentPath;
    }
    public String getBucket() {
        return bucket;
    }
    public void setBucket(String bucket) {
        this.bucket = bucket;
    }
}

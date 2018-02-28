package com.armchairoctopus.s3ftps;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Vector;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.CopyObjectRequest;
import com.amazonaws.services.s3.model.DeleteObjectRequest;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelSftp.LsEntry;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Sync implements RequestHandler<Request, String> {

    // Initialize the Log4j logger.
    static final Logger logger = LogManager.getLogger(Sync.class);
    final static String SFTP_GET = "get";
    final static String SFTP_PUT = "put";

    @Override
    public String handleRequest(Request request, Context context) {

        logger.debug("Operation: " + request.getOperation());
        if (request.getOperation().equals(SFTP_GET)) {
            getFiles(request);
        } else if (request.getOperation().equals(SFTP_PUT)) {
            putFiles(request);
        } else {
            logger.error("Unknown operation " + request.getOperation());
        }

        logger.debug("Success");
        return "Success";
    }

    private void getFiles(Request request) {
        logger.debug("Getting files via sftp");
        try {
            Session session = getJschSession(request);
            Channel channel = session.openChannel("sftp");
            channel.connect();
            ChannelSftp sftpChannel = (ChannelSftp) channel;
            Vector<LsEntry> list = sftpLs(sftpChannel, request.getDownloadPath());
            processList(sftpChannel, list, request);
            sftpChannel.exit();
            session.disconnect();
        } catch (JSchException e) {
            logger.error("Error:" + e);
        }
    }
    
    private Session getJschSession(Request request) {
        
        logger.debug("Creating JSch session");
        JSch jsch = new JSch();
        byte[] key = Base64.getDecoder().decode(request.getHostKey()); 
        try {
            HostKey hostKey = new HostKey(request.host, key);
            jsch.getHostKeyRepository().add(hostKey, null);
        } catch (JSchException e) {
            logger.error("Error:" + e);
        }
        Session session = null;
        try {
            session = jsch.getSession(request.getUser(), request.getHost());

        /*------- This is for testing only! -------*/
     //   java.util.Properties config = new java.util.Properties();
     //   config.put("StrictHostKeyChecking", "no");
    //    session.setConfig(config);
        /*------------------end---------------------*/

        // Relies in host key being in known-hosts file
        session.setPassword(request.getPassword());
        session.connect();
        } catch (JSchException e) {
            logger.error("Error:" + e);
        }
        return session;
    }

    private void processList(ChannelSftp sftpChannel, Vector<LsEntry> list, Request request) {
        for (ChannelSftp.LsEntry oListItem : list) {
            logger.info(oListItem.toString());

            if (!oListItem.getAttrs().isDir()) {
                logger.info("Syncing " + oListItem.getFilename());
                InputStream stream;
                try {
                    stream = sftpChannel.get(oListItem.getFilename());
                    writeToS3(request.getBucket(), request.getDownloadPath() + "/" + oListItem.getFilename(), stream);
                } catch (SftpException e) {
                    logger.error("Error:" + e);
                }
                // Delete remote file
                // sftpChannel.rm(oListItem.getFilename()); // Uncomment to delete files on remote host
            }
        }
    }

    private Vector<LsEntry> sftpLs(ChannelSftp channel, String path) {
        logger.debug("changing directory to " + path);

        try {
            channel.cd(path);
            logger.info("cd " + channel.lpwd());

            @SuppressWarnings("unchecked")
            Vector<ChannelSftp.LsEntry> list = channel.ls(".");
            logger.info("ls .");
            return list;

        } catch (SftpException e) {
            logger.error("Error: " + e);
        }
        return null;
    }

    private void putFiles(Request request) {
        AmazonS3 s3client = AmazonS3ClientBuilder.standard().withRegion(Regions.US_EAST_2).build();
        List<String> keys = getUploadObjectsFromS3(request.getUploadPath(), request.getBucket(), s3client);

        if (keys.size() == 0) {
            logger.debug("No files to upload");
            return;
        }
        
        logger.debug("Putting files via sftp");
        try {
            Session session = getJschSession(request);
            Channel channel = session.openChannel("sftp");
            channel.connect();
            ChannelSftp sftpChannel = (ChannelSftp) channel;
            sftpChannel.cd(request.getUploadPath());
            logger.info("cd " + request.getUploadPath());
            
            for (String key : keys) {
                logger.debug("Found object with key " + key);
                S3Object object = s3client.getObject(new GetObjectRequest(request.getBucket(), key));
                InputStream objectData = object.getObjectContent();
                String[] keyArray = key.split("/");
                String fileName = keyArray[keyArray.length - 1];
                if (!fileName.equals(request.getUploadPath())) { //TODO: this is to prevent upload of the prefix, but needs fixing                   
                    logger.info("Uploading " + fileName);
                    sftpChannel.put(objectData, fileName);
                    logger.debug("Moving file to " + request.getSentPath());
                    String oldKey = key;
                    String newKey = key.replace(request.getUploadPath(), request.getSentPath());
                    s3client.copyObject(new CopyObjectRequest(request.getBucket(), oldKey, request.getBucket(), newKey));
                    s3client.deleteObject(new DeleteObjectRequest(request.getBucket(), oldKey));
                }
            }
            sftpChannel.exit();
            session.disconnect();
        } catch (JSchException e) {
            logger.error("Error:" + e);
        } catch (SftpException e) {
            logger.error("Error:" + e);
        }
        
    }

    private static void writeToS3(String bucketName, String key, InputStream stream) {
        logger.debug("Creating s3client");
        AmazonS3 s3client = AmazonS3ClientBuilder.standard().withRegion(Regions.US_EAST_2).build();

        logger.debug("Uploading to S3");
        ObjectMetadata meta = new ObjectMetadata();
        s3client.putObject(bucketName, key, stream, meta);
    }
    
    private List<String> getUploadObjectsFromS3(String uploadPath, String bucketName, AmazonS3 s3client) {
        logger.debug("Getting files to upload");
        ListObjectsRequest listObjectsRequest = 
                                    new ListObjectsRequest()
                                          .withBucketName(bucketName)
                                          .withPrefix(uploadPath + "/")
                                          .withDelimiter("/");
     
        List<String> keys = new ArrayList<>();
        ObjectListing objects = s3client.listObjects(listObjectsRequest);
        
        while (objects.getObjectSummaries().size() > 0) {
            List<S3ObjectSummary> summaries = objects.getObjectSummaries();
            summaries.forEach(s -> keys.add(s.getKey()));
            objects = s3client.listNextBatchOfObjects(objects);
        }
        return keys;
    }
}
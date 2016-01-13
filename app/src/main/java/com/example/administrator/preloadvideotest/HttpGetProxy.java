package com.example.administrator.preloadvideotest;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import android.util.Log;

/**
 * 代理服务器类
 * @author hellogv
 *
 */
public class HttpGetProxy{
	final static public String TAG = "HttpGetProxy";
	/** 链接带的端口 */
	private int remotePort=-1;
	/** 远程服务器地址 */
	private String remoteHost;
	/** 代理服务器使用的端口 */
	private int localPort;
	/** 本地服务器地址 */
	private String localHost;
	private ServerSocket localServer = null;
	/** 收发Media Player请求的Socket */
	private Socket sckPlayer = null;
	/** 收发Media Server请求的Socket */
	private Socket sckServer = null;
	
	private SocketAddress address;
	
	/**下载线程*/
	private DownloadThread download = null;
	/**
	 * 初始化代理服务器
	 * 
	 * @param localport 代理服务器监听的端口
	 */
	public HttpGetProxy(int localport) {
		try {
			localPort = localport;
			localHost = C.LOCAL_IP_ADDRESS;
			localServer = new ServerSocket(localport, 1,InetAddress.getByName(localHost));
		} catch (Exception e) {
			System.exit(0);
		}
	}

	/**
	 * 把URL提前下载在SD卡，实现预加载
	 * @param urlString
	 * @return 返回预加载文件名
	 * @throws Exception
	 */
	public String prebuffer(String urlString,int size) throws Exception{
		if(download!=null && download.isDownloading())
			download.stopThread(true);
		
		URI tmpURI=new URI(urlString);
		String fileName=ProxyUtils.urlToFileName(tmpURI.getPath());
		String filePath=C.getBufferDir()+"/"+fileName;
		
		download=new DownloadThread(urlString,filePath,size);
		download.startThread();
		
		return filePath;
	}
	
	/**
	 * 把网络URL转为本地URL，127.0.0.1替换网络域名
	 *
	 * @param //url网络URL
	 * @return [0]:重定向后MP4真正URL，[1]:本地URL
	 */
	public String[] getLocalURL(String urlString) {
		
		// ----排除HTTP特殊----//
		String targetUrl = ProxyUtils.getRedirectUrl(urlString);
		// ----获取对应本地代理服务器的链接----//
		String localUrl = null;
		URI originalURI = URI.create(targetUrl);
		remoteHost = originalURI.getHost();
		if (originalURI.getPort() != -1) {// URL带Port
			address = new InetSocketAddress(remoteHost, originalURI.getPort());// 使用默认端口
			remotePort = originalURI.getPort();// 保存端口，中转时替换
			localUrl = targetUrl.replace(
					remoteHost + ":" + originalURI.getPort(), localHost + ":"
							+ localPort);
		} else {// URL不带Port
			address = new InetSocketAddress(remoteHost, C.HTTP_PORT);// 使用80端口
			remotePort = -1;
			localUrl = targetUrl.replace(remoteHost, localHost + ":"
					+ localPort);
		}
		
		String[] result= new String[]{targetUrl,localUrl};
		return result;
	}

	/**
	 * 异步启动代理服务器
	 * 
	 * @throws IOException
	 */
	public void asynStartProxy() {

		new Thread() {
			public void run() {
				startProxy();
			}
		}.start();
	}

	private void startProxy() {
		HttpParser httpParser =null;
		int bytes_read;
		boolean enablePrebuffer=false;//必须放在这里
		
		byte[] local_request = new byte[1024];
		byte[] remote_reply = new byte[1024];

		while (true) {
			boolean hasResponseHeader = false;
			try {// 开始新的request之前关闭过去的Socket
				if (sckPlayer != null)
					sckPlayer.close();
				if (sckServer != null)
					sckServer.close();
			} catch (IOException e1) {}
			try {
				// --------------------------------------
				// 监听MediaPlayer的请求，MediaPlayer->代理服务器
				// --------------------------------------
				sckPlayer = localServer.accept();
				Log.e("TAG","------------------------------------------------------------------");
				if(download!=null && download.isDownloading())
					download.stopThread(false);
				
				httpParser=new HttpParser(remoteHost,remotePort,localHost,localPort);
				
				HttpParser.ProxyRequest request = null;
				while ((bytes_read = sckPlayer.getInputStream().read(local_request)) != -1) {
					byte[] buffer=httpParser.getRequestBody(local_request,bytes_read);
					if(buffer!=null){
						request=httpParser.getProxyRequest(buffer);
						break;
					}
				}
				
				boolean isExists=new File(request._prebufferFilePath).exists();
				enablePrebuffer = isExists && request._isReqRange0;//两者具备才能使用预加载
				Log.e(TAG,"enablePrebuffer:"+enablePrebuffer);
				sentToServer(request._body);
				// ------------------------------------------------------
				// 把网络服务器的反馈发到MediaPlayer，网络服务器->代理服务器->MediaPlayer
				// ------------------------------------------------------
				boolean enableSendHeader=true;
				while ((bytes_read = sckServer.getInputStream().read(remote_reply)) != -1) {
					byte[] tmpBuffer = new byte[bytes_read];
					System.arraycopy(remote_reply, 0, tmpBuffer, 0, tmpBuffer.length);
					
					if(hasResponseHeader){
					sendToMP(tmpBuffer);
					}
					else{
						List<byte[]> httpResponse=httpParser.getResponseBody(remote_reply, bytes_read);
						if(httpResponse.size()>0){
							hasResponseHeader = true;
							if (enableSendHeader) {
								// send http header to mediaplayer
								sendToMP(httpResponse.get(0));
								String responseStr = new String(httpResponse.get(0));
								Log.e(TAG+"<---", responseStr);
							}
							if (enablePrebuffer) {//send prebuffer to mediaplayer
								int fileBufferSize = sendPrebufferToMP(request._prebufferFilePath);
								if (fileBufferSize > 0) {//重新发送请求到服务器
									String newRequestStr = httpParser.modifyRequestRange(request._body,
													fileBufferSize);
									Log.e(TAG + "-pre->", newRequestStr);
									enablePrebuffer = false;

									// 下次不处理response的http header
									sentToServer(newRequestStr);
									enableSendHeader = false;
									hasResponseHeader = false;
									continue;
								}
							}

							//发送剩余数据
							if (httpResponse.size() == 2) {
								sendToMP(httpResponse.get(1));
							}
						}
					}
				}
				Log.e(TAG, ".........over..........");

				// 关闭 2个SOCKET
				sckPlayer.close();
				sckServer.close();
			} catch (Exception e) {
				Log.e(TAG,e.toString());
				Log.e(TAG,ProxyUtils.getExceptionMessage(e));
			}
		}
	}
	
	private int sendPrebufferToMP(String fileName) throws IOException {
		int fileBufferSize=0;
		byte[] file_buffer = new byte[1024];
		int bytes_read = 0;
		FileInputStream fInputStream = new FileInputStream(fileName);
		while ((bytes_read = fInputStream.read(file_buffer)) != -1) {
			fileBufferSize += bytes_read;
			byte[] tmpBuffer = new byte[bytes_read];
			System.arraycopy(file_buffer, 0, tmpBuffer, 0, bytes_read);
			sendToMP(tmpBuffer);
		}
		fInputStream.close();
		
		Log.e(TAG,"读取完毕...下载:"+download.getDownloadedSize()+",读取:"+fileBufferSize);
		return fileBufferSize;
	}
	
	private void sendToMP(byte[] bytes) throws IOException{
			sckPlayer.getOutputStream().write(bytes);
			sckPlayer.getOutputStream().flush();
	}

	private void sentToServer(String requestStr) throws IOException{
		try {
			if(sckServer!=null)
				sckServer.close();
		} catch (Exception ex) {}
		sckServer = new Socket();
		sckServer.connect(address);
		sckServer.getOutputStream().write(requestStr.getBytes());// 发送MediaPlayer的请求
		sckServer.getOutputStream().flush();
	}
}
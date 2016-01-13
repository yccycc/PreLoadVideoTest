package com.example.administrator.preloadvideotest;

import java.io.File;
import android.app.Activity;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnPreparedListener;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.widget.MediaController;
import android.widget.VideoView;

public class TestVideoPlayer extends Activity{
	private final static String TAG="TestVideoPlayer";
	private VideoView mVideoView;
	private MediaController mediaController;
	private HttpGetProxy proxy;
	private long startTimeMills;
	private String proxyUrl;
	private String oriVideoUrl ="http://192.168.35.85:8080/Dss/sexy_love.3gp";

	@Override
	public void onCreate(Bundle icicle) {
		super.onCreate(icicle);
		setContentView(R.layout.main);
		setTitle("hello yc");
		new File(C.getBufferDir()).mkdirs();//创建预加载文件的文件夹
		ProxyUtils.clearCacheFile(C.getBufferDir());//清除前面的预加载文件
		
		//初始化VideoView
		mediaController=new MediaController(this);
		mVideoView = (VideoView) findViewById(R.id.surface_view);
		mVideoView.setMediaController(mediaController);
		mVideoView.setOnPreparedListener(mOnPreparedListener);
		//初始化代理服务器
		proxy = new HttpGetProxy(9980);
		proxy.asynStartProxy();
		new Thread(){
			@Override
			public void run() {
				super.run();
				String[] urls = proxy.getLocalURL(oriVideoUrl);
				String mp4Url = urls[0];
				proxyUrl = urls[1];
				boolean enablePrebuffer = true;//纯粹对比测试
				if (enablePrebuffer) {//使用预加载
					try {
						String prebufferFilePath = proxy.prebuffer(mp4Url,
								5 * 1024 * 1024);
						Log.e(TAG, "预加载文件：" + prebufferFilePath);
					} catch (Exception ex) {
						Log.e(TAG, ex.toString());
						Log.e(TAG, ProxyUtils.getExceptionMessage(ex));
					}
					delayToStartPlay.sendEmptyMessageDelayed(0, 8000);//留8000ms预加载
				} else//不使用预加载
					delayToStartPlay.sendEmptyMessageDelayed(0, 0);

				// 一直显示MediaController
				showController.sendEmptyMessageDelayed(0, 1000);
			}
		}.start();;
	}
	
	@Override
	public void onStop(){
		super.onStop();
		finish();
		System.exit(0);
	}
	
	private OnPreparedListener mOnPreparedListener=new OnPreparedListener(){

		@Override
		public void onPrepared(MediaPlayer mp) {
			mVideoView.start();
			long duration=System.currentTimeMillis() - startTimeMills;
			Log.e("duration:",duration+"");
		}
	};
	
	private Handler delayToStartPlay = new Handler() {
		public void handleMessage(Message msg) {
			startTimeMills=System.currentTimeMillis();
			mVideoView.setVideoPath(proxyUrl);
		}
	};
	
	private Handler showController = new Handler() {
		public void handleMessage(Message msg) {
			mediaController.show(0);
		}
	};
}
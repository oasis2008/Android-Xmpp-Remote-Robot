package com.dary.xmpp;

import org.jivesoftware.smack.Chat;
import org.jivesoftware.smack.ChatManager;
import org.jivesoftware.smack.Connection;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.packet.Presence;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;

import com.dary.xmpp.receivers.BatteryReceiver;
import com.dary.xmpp.receivers.ConnectionChangeReceiver;
import com.dary.xmpp.receivers.SMSReceiver;

public class MainService extends Service {

	public static boolean isloginin = false;
	public static Connection connection;
	private ConnectionConfiguration config;
	public static int intLevel;
	public static int intScale;
	public static String strPlugged = "";
	private String notifiedAddress;
	private String loginAddress;
	private String password;
	public Handler myHandler;
	private String serverHost;
	private String serverPort;
	private String resource;
	private boolean isautoReconnect;
	private boolean isDebugMode;
	public SMSReceiver smsReceiver = new SMSReceiver();
	private BatteryReceiver batteryReceiver = new BatteryReceiver();
	private ConnectionChangeReceiver connectionChangeReceiver;
	private boolean iscustomServer;
	private Context mContext = this;

	public static Chat chat;

	@Override
	public void onCreate() {
		// 启动InCallService
		Intent incallserviceIntent = new Intent();
		incallserviceIntent.setClass(MainService.this, IncallService.class);
		startService(incallserviceIntent);
		super.onCreate();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		IncallService.isFirstStart = true;

		// 判断配置是否都已填入
		getSetting();

		// 如果配置不全,显示Toast
		if (loginAddress.equals("") || password.equals("") || notifiedAddress.equals("")) {
			sendMsg(XmppActivity.SET_INCOMPLETE);
		}
		// 否则才登录
		else {
			// 启动登录线程
			LoginInThread logininthread = new LoginInThread();
			Thread thread = new Thread(logininthread);
			thread.setName("LoginThread");
			thread.start();
			System.out.println("登录线程开始运行");

			// 尝试将登录的记录存储下来,先暂时只存储到普通的文本文件中
			Tools.doLog("Login");
			// 登录中,发送消息,更新UI.
			sendMsg(XmppActivity.LOGGING);
		}
		return super.onStartCommand(intent, flags, startId);
	}

	// 登录线程
	class LoginInThread implements Runnable {

		public void run() {
			if (iscustomServer) {
				config = new ConnectionConfiguration(serverHost, Integer.parseInt(serverPort));
			} else {
				config = new ConnectionConfiguration(serverHost);
			}

			// config.setTruststorePath("/system/etc/security/cacerts.bks");
			// config.setTruststorePassword("changeit");
			// config.setTruststoreType("bks");
			// config.setSecurityMode(ConnectionConfiguration.SecurityMode.disabled);
			// config.setReconnectionAllowed(false);
			// config.setSendPresence(false);
			// config.setCompressionEnabled(false);
			config.setSASLAuthenticationEnabled(true);

			connection = new XMPPConnection(config);
			try {
				System.out.println("与服务器建立连接");
				connection.connect();

				try {
					// 防止重新连接时多次登录.
					if (!connection.isAuthenticated() && connection.isConnected()) {
						
						System.out.println("登录,验证口令");

						// connection.login(loginAddress,password,resource);
						connection.login(loginAddress, password, Tools.getTimeStr());

						isloginin = true;

						// Tools.Vibrator(MainService.this, 500);

						System.out.println("登录成功");
						Tools.doLog("Login Successful");
						makeNotification("Login Successful");
						// 登录成功后发送消息通知Activity改变按钮状态
						sendMsg(XmppActivity.LOGIN_SUCCESSFUL);

						ChatManager chatmanager = connection.getChatManager();

						// 注册消息监听器
						chat = chatmanager.createChat(notifiedAddress.toLowerCase(), new MsgListener());

						// 登录成功之后再在程序动态的注册电量改变,短信和连接改变的广播接收器,注册电量改变的接收器时会设置Presence
						registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

						// 这里必须判断监听网络连接的广播接收器是否已经注册了,否则会反复注册.导致收到广播的时候多次登录
						if (isautoReconnect && connectionChangeReceiver == null) {
							connectionChangeReceiver = new ConnectionChangeReceiver();
							registerReceiver(connectionChangeReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
						}

						registerReceiver(smsReceiver, new IntentFilter("android.provider.Telephony.SMS_RECEIVED"));

						// 登录成功后发送消息,用于测试
						if (isDebugMode) {
							SendMessageAndUpdateView.sendMessageAndUpdateView(chat, "Login is successful");
						}
					}

				} catch (XMPPException e) {
					System.out.println("登录失败");
					Tools.doLog("Login Failed");
					makeNotification("Login Failed");
					sendMsg(XmppActivity.LOGIN_FAILED);
					e.printStackTrace();
				}
			} catch (XMPPException e) {
				System.out.println("连接服务器失败");
				Tools.doLog("Connection Failed");
				makeNotification("Connection Failed");
				sendMsg(XmppActivity.CONNECTION_FAILED);
				e.printStackTrace();
			}
		}
	}

	@Override
	public void onDestroy() {
		if (connection.isConnected()) {
			Presence presence = new Presence(Presence.Type.unavailable);
			connection.sendPacket(presence);
			connection.disconnect();
		}
		// 反注册广播接收器
		unregisterReceiver(batteryReceiver);
		unregisterReceiver(smsReceiver);
		if (isautoReconnect) {
			unregisterReceiver(connectionChangeReceiver);
		}
		isloginin = false;
		super.onDestroy();
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	private void getSetting() {
		iscustomServer = getApplicationContext().getSharedPreferences("com.dary.xmpp_preferences", Activity.MODE_PRIVATE).getBoolean("isCustomServer", false);
		System.out.println("自定义服务器设置 " + iscustomServer);
		serverHost = getApplicationContext().getSharedPreferences("com.dary.xmpp_preferences", Activity.MODE_PRIVATE).getString("serverHost", "");
		System.out.println("服务器主机 " + serverHost);
		serverPort = getApplicationContext().getSharedPreferences("com.dary.xmpp_preferences", Activity.MODE_PRIVATE).getString("serverPort", "5222");
		System.out.println("服务器端口 " + serverPort);
		loginAddress = getApplicationContext().getSharedPreferences("com.dary.xmpp_preferences", Activity.MODE_PRIVATE).getString("loginAddress", "");
		System.out.println("登录地址 " + loginAddress);
		password = getApplicationContext().getSharedPreferences("com.dary.xmpp_preferences", Activity.MODE_PRIVATE).getString("password", "");
		System.out.println("密码 " + password);
		notifiedAddress = getApplicationContext().getSharedPreferences("com.dary.xmpp_preferences", Activity.MODE_PRIVATE).getString("notifiedAddress", "");
		System.out.println("提醒地址 " + notifiedAddress);
		resource = getApplicationContext().getSharedPreferences("com.dary.xmpp_preferences", Activity.MODE_PRIVATE).getString("resource", "");
		System.out.println("资源名 " + resource);
		isautoReconnect = getApplicationContext().getSharedPreferences("com.dary.xmpp_preferences", Activity.MODE_PRIVATE).getBoolean("isAutoReconnect", true);
		System.out.println("是否重新连接 " + isautoReconnect);
		isDebugMode = getApplicationContext().getSharedPreferences("com.dary.xmpp_preferences", Activity.MODE_PRIVATE).getBoolean("isDebugMode", false);
		System.out.println("调试模式 " + isDebugMode);
	}

	private void sendMsg(int tag) {
		// 登录中,发送消息,更新UI.
		if (null != XmppActivity.MsgHandler) {
			Message msg = new Message();
			msg.what = tag;
			XmppActivity.MsgHandler.sendMessage(msg);
		}
	}

	private void makeNotification(String str) {
		NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		Notification notification = new Notification(R.drawable.ic_launcher, str, System.currentTimeMillis());
		notification.flags = Notification.FLAG_AUTO_CANCEL;
		Intent intent = new Intent(mContext, XmppActivity.class);
		PendingIntent contentIntent = PendingIntent.getActivity(mContext, 0, intent, 0);
		notification.setLatestEventInfo(mContext, str, str, contentIntent);
		notificationManager.notify(R.drawable.ic_launcher, notification);
	}

}
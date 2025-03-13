package com.iflytek.voicecloud.rtasr;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.iflytek.voicecloud.rtasr.util.EncryptUtil;
import org.java_websocket.WebSocket.READYSTATE;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft;
import org.java_websocket.handshake.ServerHandshake;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;


public class RTASRTest {

    // appid 与 secret_key
    private static final String APPID = "";
    private static final String SECRET_KEY = "";
    private static final String DEEPSEEK_API_KEY = "";
    private static final String DEEPSEEK_API_URL = "https://api.deepseek.com/v1/chat/completions";

    // 请求地址及其他参数
    private static final String HOST = "rtasr.xfyun.cn/v1/ws";
    private static final String BASE_URL = "wss://" + HOST;
    private static final String ORIGIN = "https://" + HOST;
    private static final int CHUNCKED_SIZE = 1280;
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyy-MM-dd HH:mm:ss.SSS");

    // 用于存储最新的语音转写结果（线程安全）
    private static final AtomicReference<String> latestResult = new AtomicReference<>("");
    // 用于储存多轮对话记录
    private static JSONArray conversationHistory = new JSONArray();

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.println("按'y' 键开始录音，或输入 'n' 退出程序...");
            String input = scanner.nextLine();
            if ("n".equalsIgnoreCase(input.trim())) {
                System.out.println("程序退出...");
                break;
            }else if("y".equalsIgnoreCase(input.trim())) {
                System.out.println("开始录音... 再次按回车键结束录音");
                String result = startSpeechToText(scanner);
                System.out.println("语音转写结果：" + result);
                // 1. 将用户输入添加到对话历史
                JSONObject userMessage = new JSONObject();
                userMessage.put("role", "user");
                userMessage.put("content", result+"请假设你是一个正在面试客户端开发的实习生，尽量简短回答且结合Java和C++的相关知识");
                conversationHistory.add(userMessage); // FastJSON 的 add 方法
                // 2. 调用 API 并获取回复
                try {
                    String aiResponse = callDeepSeekAPI(conversationHistory);
                    System.out.println("\nDeepSeek 回答：\n" + aiResponse);

                    // 3. 将 AI 回复添加到对话历史
                    JSONObject assistantMessage = new JSONObject();
                    assistantMessage.put("role", "user");
                    assistantMessage.put("content", aiResponse);
                    conversationHistory.add(assistantMessage);
                } catch (Exception e) {
                    System.err.println(e.getMessage());
                    // 可选：失败时移除最后一条用户消息
                    conversationHistory.remove(conversationHistory.size() - 1);
                }
            }
        }
    }

    // 讲话转文字，返回最新转写结果
    public static String startSpeechToText(Scanner scanner) throws Exception {
        // 建立 WebSocket 连接
        URI url = new URI(BASE_URL + getHandShakeParams(APPID, SECRET_KEY));
        CountDownLatch handshakeSuccess = new CountDownLatch(1);
        CountDownLatch connectClose = new CountDownLatch(1);
        DraftWithOrigin draft = new DraftWithOrigin(ORIGIN);
        MyWebSocketClient client = new MyWebSocketClient(url, draft, handshakeSuccess, connectClose);
        client.connect();

        // 等待 WebSocket 连接建立
        while (!client.getReadyState().equals(READYSTATE.OPEN)) {
            System.out.println(getCurrentTimeStr() + "\t连接中...");
            Thread.sleep(1000);
        }
        // 等待握手成功
        handshakeSuccess.await();
        System.out.println(sdf.format(new Date()) + " 连接成功，开始讲话...");

        // 配置音频采集参数（采样率16k、16位、单声道）
        float rate = 16000.0F;
        int sizeInBits = 16;
        int channels = 1;
        AudioFormat audioFormat = new AudioFormat(rate, sizeInBits, channels, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, audioFormat);
        TargetDataLine targetDataLine = (TargetDataLine) AudioSystem.getLine(info);
        targetDataLine.open(audioFormat);
        targetDataLine.start();

        final int bufSize = CHUNCKED_SIZE;
        byte[] buffer = new byte[bufSize];

        // 使用标志变量控制录音循环
        final boolean[] capturing = new boolean[]{true};

        Thread recordingThread = new Thread(() -> {
            try {
                // 循环采集并发送音频数据
                while (capturing[0] && targetDataLine.read(buffer, 0, bufSize) > 0) {
                    send(client, buffer);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        recordingThread.start();

        // 等待用户再次按回车，表示结束录音
        scanner.nextLine();
        capturing[0] = false;
        recordingThread.join();

        // 关闭录音设备和 WebSocket 连接
        targetDataLine.stop();
        targetDataLine.close();
        client.close();

        // 返回当前累积的转写结果（在 MyWebSocketClient.onMessage() 中更新）
        return getFinalSpeechResult();
    }

    // 生成握手参数
    public static String getHandShakeParams(String appId, String secretKey) {
        String ts = System.currentTimeMillis() / 1000 + "";
        String signa = "";
        try {
            signa = EncryptUtil.HmacSHA1Encrypt(EncryptUtil.MD5(appId + ts), secretKey);
            return "?appid=" + appId + "&ts=" + ts + "&signa=" + URLEncoder.encode(signa, "UTF-8") + "&vadMdn=2";
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    // 发送音频数据
    public static void send(WebSocketClient client, byte[] bytes) {
        if (client.isClosed()) {
            throw new RuntimeException("client connect closed!");
        }
        client.send(bytes);
    }

    public static String getCurrentTimeStr() {
        return sdf.format(new Date());
    }

    // 在类级别定义两个静态变量存储结果：
    private static String fullResult = "";
    private static String currentSegment = "";

    // 返回最新的转写结果
    private static String getFinalSpeechResult() {
        synchronized (MyWebSocketClient.class) {
            String  res = fullResult + " " + currentSegment;
            fullResult = "";
            currentSegment = "";
            return res;
        }
    }

    // WebSocket 客户端类
    public static class MyWebSocketClient extends WebSocketClient {

        private CountDownLatch handshakeSuccess;
        private CountDownLatch connectClose;

        public MyWebSocketClient(URI serverUri, Draft protocolDraft, CountDownLatch handshakeSuccess, CountDownLatch connectClose) {
            super(serverUri, protocolDraft);
            this.handshakeSuccess = handshakeSuccess;
            this.connectClose = connectClose;
            if (serverUri.toString().contains("wss")) {
                trustAllHosts(this);
            }
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            System.out.println(getCurrentTimeStr() + "\t连接建立成功！");
        }

        @Override
        public void onMessage(String msg) {
            JSONObject msgObj = JSON.parseObject(msg);
            String action = msgObj.getString("action");
            if (Objects.equals("started", action)) {
                // 握手成功
                System.out.println(getCurrentTimeStr() + "\t握手成功！sid: " + msgObj.getString("sid"));
                handshakeSuccess.countDown();
            } else if (Objects.equals("result", action)) {
                // 获取本次收到的转写文本
                String newResult = getContent(msgObj.getString("data"));
                System.out.println(getCurrentTimeStr() + "\tresult: " + newResult);
                synchronized (MyWebSocketClient.class) {
                    // 如果当前段为空，直接赋值
                    if (currentSegment.isEmpty()) {
                        currentSegment = newResult;
                    } else {
                        // 如果新结果是当前段的扩展（即以 currentSegment 为前缀），则更新 currentSegment
                        if (newResult.startsWith(currentSegment) && newResult.length() >= currentSegment.length()) {
                            currentSegment = newResult;
                        } else {
                            // 否则认为前一段完成，将其追加到 fullResult 后，再更新 currentSegment
                            if (!fullResult.isEmpty()) {
                                fullResult += "：";
                            }
                            fullResult += currentSegment;
                            currentSegment = newResult;
                        }
                    }
                }
            } else if (Objects.equals("error", action)) {
                System.out.println("Error: " + msg);
                System.exit(0);
            }
        }

        @Override
        public void onError(Exception e) {
            System.out.println(getCurrentTimeStr() + "\t连接发生错误：" + e.getMessage() + ", " + new Date());
            e.printStackTrace();
            System.exit(0);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            System.out.println(getCurrentTimeStr() + "\t链接关闭");
            connectClose.countDown();
        }

        @Override
        public void onMessage(ByteBuffer bytes) {
            try {
                System.out.println(getCurrentTimeStr() + "\t服务端返回：" + new String(bytes.array(), "UTF-8"));
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();

            }
        }

        public void trustAllHosts(MyWebSocketClient client) {
            System.out.println("wss");
            TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[]{};
                }
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {}
                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {}
            }};
            try {
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, trustAllCerts, new java.security.SecureRandom());
                client.setSocket(sc.getSocketFactory().createSocket());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // 解析转写结果中的文本
    public static String getContent(String message) {
        StringBuffer resultBuilder = new StringBuffer();
        try {
            JSONObject messageObj = JSON.parseObject(message);
            JSONObject cn = messageObj.getJSONObject("cn");
            JSONObject st = cn.getJSONObject("st");
            JSONArray rtArr = st.getJSONArray("rt");
            for (int i = 0; i < rtArr.size(); i++) {
                JSONObject rtArrObj = rtArr.getJSONObject(i);
                JSONArray wsArr = rtArrObj.getJSONArray("ws");
                for (int j = 0; j < wsArr.size(); j++) {
                    JSONObject wsArrObj = wsArr.getJSONObject(j);
                    JSONArray cwArr = wsArrObj.getJSONArray("cw");
                    for (int k = 0; k < cwArr.size(); k++) {
                        JSONObject cwArrObj = cwArr.getJSONObject(k);
                        String wStr = cwArrObj.getString("w");
                        resultBuilder.append(wStr);
                    }
                }
            }
        } catch (Exception e) {
            return message;
        }
        return resultBuilder.toString();
    }

    // 新增DeepSeek API调用方法（Java 8 兼容）
    private static String callDeepSeekAPI(JSONArray messages) throws IOException {
        HttpURLConnection conn = null;
        BufferedReader br = null;
        OutputStream os = null;
        try {
            URL url = new URL(DEEPSEEK_API_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + DEEPSEEK_API_KEY);
            conn.setDoOutput(true);

            // 构建请求体
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", "deepseek-chat");
            requestBody.put("messages", messages);

            // 发送请求
            os = conn.getOutputStream();
            byte[] input = requestBody.toJSONString().getBytes("utf-8");
            os.write(input, 0, input.length);

            // 检查 HTTP 状态码
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                throw new IOException("HTTP 错误代码: " + responseCode);
            }

            // 解析响应
            br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }

            // FastJSON 解析
            JSONObject jsonResponse = JSON.parseObject(response.toString());
            return jsonResponse.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");
        } finally {
            // 关闭资源
            if (os != null) try { os.close(); } catch (IOException e) { e.printStackTrace(); }
            if (br != null) try { br.close(); } catch (IOException e) { e.printStackTrace(); }
            if (conn != null) conn.disconnect();
        }
    }
}





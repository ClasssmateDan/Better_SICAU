package com.dan.kejian;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Loopback CONNECT transport for one origin. TLS remains entirely inside WebView. */
final class SigninIpv4Tunnel implements Closeable {
    static final String HOST = "eol.sicau.edu.cn";
    interface Resolver { InetAddress[] resolve() throws IOException; }
    interface Connector { Socket connect(InetAddress address) throws IOException; }
    private final Resolver resolver;
    private final Connector connector;
    private final ServerSocket server;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Semaphore permits = new Semaphore(6);
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(0, 16, 30, TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>(), task -> { Thread thread = new Thread(task,"signin-ipv4-pipe");thread.setDaemon(true);return thread; });
    private final AtomicInteger connected = new AtomicInteger();
    private volatile boolean closed;
    private volatile String lastAddress = "", lastError = "";
    private volatile long lastConnectMs;

    SigninIpv4Tunnel() throws IOException { this(() -> InetAddress.getAllByName(HOST), null); }
    SigninIpv4Tunnel(Resolver resolver, Connector connector) throws IOException {
        this.resolver = resolver; this.connector = connector;
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        Thread acceptor = new Thread(this::accept,"signin-ipv4-listener");acceptor.setDaemon(true);acceptor.start();
    }
    int port() { return server.getLocalPort(); }
    String summary() {
        return "IPv4 通道已建立："+connected.get()+"\n最近目标 IPv4："+(lastAddress.isEmpty()?"尚无":lastAddress)+
                (lastAddress.isEmpty()?"":" / TCP "+lastConnectMs+" ms")+"\n"+(lastError.isEmpty()?"":"最近通道异常："+lastError+"\n");
    }
    private void accept() {
        while (!closed) {
            Socket client = null;
            try {
                client = server.accept(); register(client);
                if (!permits.tryAcquire()) { reject(client,503); closeSocket(client); continue; }
                final Socket accepted = client;
                try { workers.execute(() -> tunnel(accepted)); }
                catch (RuntimeException error) { permits.release(); closeSocket(accepted); }
            } catch (IOException error) { if(client!=null)closeSocket(client);if(closed)return; }
        }
    }
    private void tunnel(Socket client) {
        Socket upstream = null; boolean established = false;
        try {
            client.setSoTimeout(4000);
            String header = readHeaders(client.getInputStream());
            String first = header.substring(0, header.indexOf("\r\n"));
            String[] parts = first.split(" ",-1);
            if (parts.length != 3 || !parts[0].equals("CONNECT") || !parts[1].equalsIgnoreCase(HOST+":443") ||
                    !(parts[2].equals("HTTP/1.1") || parts[2].equals("HTTP/1.0"))) { reject(client,403);return; }
            IOException failure = new IOException("No IPv4 address");
            int attempts=0;
            for(InetAddress address:resolver.resolve()) {
                if (!(address instanceof Inet4Address) || closed) continue;
                long started = System.nanoTime();
                try {
                    upstream = connector == null ? connect(address) : connector.connect(address);
                    register(upstream); lastAddress=address.getHostAddress(); lastConnectMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
                    lastError=""; connected.incrementAndGet(); break;
                } catch (IOException error) { failure=error; if(++attempts>=3)break; }
            }
            if(upstream==null)throw failure;
            client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush(); established=true;
            client.setSoTimeout(120000); upstream.setSoTimeout(120000);
            client.setTcpNoDelay(true); upstream.setTcpNoDelay(true);
            final Socket remote = upstream;
            workers.execute(() -> relay(client,remote));
            relay(remote,client);
        } catch (Exception error) { lastError=error.getClass().getSimpleName();if(!established)reject(client,502); }
        finally { closeSocket(client);if(upstream!=null)closeSocket(upstream);permits.release(); }
    }
    private Socket connect(InetAddress address) throws IOException {
        Socket socket=new Socket();register(socket);
        try { socket.connect(new InetSocketAddress(address,443),3000);return socket; }
        catch(IOException error){closeSocket(socket);throw error;}
    }
    private void register(Socket socket) throws IOException {
        if(closed){socket.close();throw new IOException("Tunnel closed");}
        sockets.add(socket);
        if(closed){closeSocket(socket);throw new IOException("Tunnel closed");}
    }
    private void closeSocket(Socket socket) { sockets.remove(socket);try{socket.close();}catch(IOException ignored){} }
    private void relay(Socket from, Socket to) {
        try {
            byte[] bytes=new byte[8192];int length;
            while((length=from.getInputStream().read(bytes))!=-1)to.getOutputStream().write(bytes,0,length);
            try{to.shutdownOutput();}catch(IOException ignored){}
        } catch(IOException error) { closeSocket(from);closeSocket(to); }
    }
    private static void reject(Socket socket,int code){
        try{socket.getOutputStream().write(("HTTP/1.1 "+code+" Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));}catch(IOException ignored){}
    }
    static String readHeaders(InputStream input) throws IOException {
        StringBuilder result=new StringBuilder();int ending=0;
        for(int size=0;size<8192;size++) {
            int value=input.read();if(value<0)throw new IOException("Incomplete CONNECT request");result.append((char)value);
            if(value=="\r\n\r\n".charAt(ending)){if(++ending==4)return result.toString();}
            else ending=value=='\r'?1:0;
        }
        throw new IOException("CONNECT header too large");
    }
    @Override public void close() {
        closed=true;try{server.close();}catch(IOException ignored){}
        for(Socket socket:sockets)closeSocket(socket);
        workers.shutdownNow();
    }
}

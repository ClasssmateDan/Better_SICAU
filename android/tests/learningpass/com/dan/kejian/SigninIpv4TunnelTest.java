package com.dan.kejian;

import java.io.ByteArrayInputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Loopback-only tests: the fake connector never contacts the school or the internet. */
public class SigninIpv4TunnelTest {
    private static final String CONNECT="CONNECT eol.sicau.edu.cn:443 HTTP/1.1\r\nHost: eol.sicau.edu.cn:443\r\n\r\n";
    private Socket client(SigninIpv4Tunnel tunnel) throws Exception{Socket s=new Socket("127.0.0.1",tunnel.port());s.setSoTimeout(3000);return s;}
    @Test public void connectParserNeverConsumesPipelinedTlsBytes() throws Exception{
        byte[] prefix=CONNECT.getBytes(StandardCharsets.US_ASCII),tls={22,3,3,0,2,0,1};
        byte[] all=new byte[prefix.length+tls.length];System.arraycopy(prefix,0,all,0,prefix.length);System.arraycopy(tls,0,all,prefix.length,tls.length);
        ByteArrayInputStream stream=new ByteArrayInputStream(all);assertEquals(CONNECT,SigninIpv4Tunnel.readHeaders(stream));
        byte[] remaining=new byte[tls.length];assertEquals(tls.length,stream.read(remaining));assertArrayEquals(tls,remaining);
    }
    @Test public void wrongHostsPortsAndMethodsNeverReachConnector() throws Exception{
        AtomicInteger calls=new AtomicInteger();
        try(SigninIpv4Tunnel tunnel=new SigninIpv4Tunnel(()->{calls.incrementAndGet();return new InetAddress[0];},null)){
            for(String line:new String[]{"CONNECT example.com:443 HTTP/1.1","CONNECT eol.sicau.edu.cn:80 HTTP/1.1","CONNECT eol.sicau.edu.cn.evil.example:443 HTTP/1.1","GET / HTTP/1.1"}){
                try(Socket s=client(tunnel)){s.getOutputStream().write((line+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));assertTrue(SigninIpv4Tunnel.readHeaders(s.getInputStream()).startsWith("HTTP/1.1 403"));}
            }
        }
        assertEquals(0,calls.get());
    }
    @Test public void ipv6OnlyResponseDoesNotFallBackToIpv6() throws Exception{
        AtomicInteger calls=new AtomicInteger();
        try(SigninIpv4Tunnel tunnel=new SigninIpv4Tunnel(()->new InetAddress[]{InetAddress.getByName("::1")},ip->{calls.incrementAndGet();return null;});Socket s=client(tunnel)){
            s.getOutputStream().write(CONNECT.getBytes(StandardCharsets.US_ASCII));assertTrue(SigninIpv4Tunnel.readHeaders(s.getInputStream()).startsWith("HTTP/1.1 502"));
        }
        assertEquals(0,calls.get());
    }
    @Test public void selectedIpv4TunnelForwardsOpaqueBytesWithoutTlsTermination() throws Exception{
        byte[] payload={22,3,3,0,5,0,1,(byte)255,0,2};AtomicInteger calls=new AtomicInteger();
        try(ServerSocket echo=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
            Thread server=new Thread(()->{try(Socket s=echo.accept()){byte[] bytes=new byte[payload.length];int at=0,n;while(at<bytes.length&&(n=s.getInputStream().read(bytes,at,bytes.length-at))>0)at+=n;s.getOutputStream().write(bytes,0,at);}catch(Exception error){throw new RuntimeException(error);}});server.setDaemon(true);server.start();
            try(SigninIpv4Tunnel tunnel=new SigninIpv4Tunnel(()->new InetAddress[]{InetAddress.getByName("::1"),InetAddress.getByName("127.0.0.1")},ip->{assertTrue(ip instanceof Inet4Address);calls.incrementAndGet();return new Socket(ip,echo.getLocalPort());});Socket s=client(tunnel)){
                s.getOutputStream().write(CONNECT.getBytes(StandardCharsets.US_ASCII));s.getOutputStream().write(payload);
                assertTrue(SigninIpv4Tunnel.readHeaders(s.getInputStream()).startsWith("HTTP/1.1 200"));
                byte[] reply=new byte[payload.length];int at=0,n;while(at<reply.length&&(n=s.getInputStream().read(reply,at,reply.length-at))>0)at+=n;
                assertArrayEquals(payload,reply);assertEquals(1,calls.get());assertTrue(tunnel.summary().contains("127.0.0.1"));
            }
            server.join(2000);assertFalse(server.isAlive());
        }
    }
    @Test public void oversizedHeaderIsBoundedAndShutdownIsIdempotent() throws Exception{
        try{SigninIpv4Tunnel.readHeaders(new ByteArrayInputStream(new byte[8193]));fail();}catch(java.io.IOException expected){}
        SigninIpv4Tunnel tunnel=new SigninIpv4Tunnel(()->new InetAddress[0],null);Socket s=client(tunnel);tunnel.close();tunnel.close();
        try{assertEquals(-1,s.getInputStream().read());}catch(java.net.SocketException expected){}finally{s.close();}
    }
}

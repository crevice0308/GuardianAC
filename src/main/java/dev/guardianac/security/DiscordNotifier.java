/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.security;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Optional, no telemetry by default. Only approved Discord webhook destinations. */
public final class DiscordNotifier implements AutoCloseable {
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(32),r->{Thread t=new Thread(r,"GuardianAC-Discord");t.setDaemon(true);return t;},new ThreadPoolExecutor.DiscardPolicy());
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final BoundedRateLimiter<String> limiter=new BoundedRateLimiter<>(8,3,.1);
    private final AtomicLong failures=new AtomicLong(); private volatile URI webhook; private volatile boolean enabled;
    public boolean configured() { return webhook!=null; }
    public void enabled(boolean enabled) { this.enabled=enabled; }
    public long failures() { return failures.get(); }
    public void load(Path directory) throws java.io.IOException {
        Path path=directory.resolve("discord-webhook.txt");webhook=null;
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return;
        if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>2048)throw new java.io.IOException("Invalid Discord secret file");
        try { URI candidate=URI.create(Files.readString(path).strip());
            if(!valid(candidate))throw new IllegalArgumentException();webhook=candidate;
        } catch(IllegalArgumentException e) { throw new java.io.IOException("Invalid Discord webhook configuration"); }
        try { Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
        catch(UnsupportedOperationException ignored) { /* OS-specific protection documented. */ }
    }
    public static boolean valid(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && "discord.com".equalsIgnoreCase(uri.getHost())
            && (uri.getPort()==-1||uri.getPort()==443)&&uri.getUserInfo()==null&&uri.getQuery()==null&&uri.getFragment()==null
            &&uri.getRawPath().matches("/api(?:/v[0-9]+)?/webhooks/[0-9]{10,30}/[A-Za-z0-9_-]{30,200}");
    }
    public void notify(String message) {
        URI target=webhook;if(!enabled||target==null||!limiter.allow("global",System.currentTimeMillis()))return;
        String bounded=message.length()>1500?message.substring(0,1500):message;
        worker.execute(()->{
            try {
                if(!enabled||!target.equals(webhook))return;
                String body="{\"content\":"+ReplayBuffer.quote(bounded)+",\"allowed_mentions\":{\"parse\":[]}}";
                var request=HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                int status=http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode();
                if(status<200||status>=300)failures.incrementAndGet();
            }catch(Exception e){failures.incrementAndGet();if(e instanceof InterruptedException)Thread.currentThread().interrupt();}
        });
    }
    @Override public void close(){ enabled=false;webhook=null;worker.shutdownNow();http.close(); }
}

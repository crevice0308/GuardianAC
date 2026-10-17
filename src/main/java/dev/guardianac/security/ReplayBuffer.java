/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.security;

import dev.guardianac.core.Sample;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Position/rotation/event evidence, never video. Explicit global and player byte limits. */
public final class ReplayBuffer {
    private record Frame(long time,String json,int bytes) {}
    private final LinkedHashMap<UUID,ArrayDeque<Frame>> players=new LinkedHashMap<>(16,.75f,true);
    private final Map<UUID,EnumMap<Sample.Kind,Long>> lastTimes=new HashMap<>();
    private final int globalLimit,playerLimit; private int total;
    public ReplayBuffer(int globalLimit,int playerLimit) {
        if(playerLimit<1024||globalLimit<playerLimit) throw new IllegalArgumentException();
        this.globalLimit=globalLimit;this.playerLimit=playerLimit;
    }
    public void add(Sample sample) {
        if(!players.containsKey(sample.playerId())&&players.size()>=Math.max(1,globalLimit/1024))forget(players.keySet().iterator().next());
        ArrayDeque<Frame> frames=players.computeIfAbsent(sample.playerId(),id->new ArrayDeque<>());
        EnumMap<Sample.Kind,Long> times=lastTimes.computeIfAbsent(sample.playerId(),id->new EnumMap<>(Sample.Kind.class));
        Long previous=times.get(sample.kind());if(previous!=null&&sample.timeMillis()-previous<150)return;
        times.put(sample.kind(),sample.timeMillis());
        String json=sampleJson(sample); int bytes=json.getBytes(StandardCharsets.UTF_8).length;
        if(bytes>playerLimit/2) return;
        Frame f=new Frame(sample.timeMillis(),json,bytes);frames.add(f);total+=bytes;
        int playerBytes=frames.stream().mapToInt(Frame::bytes).sum();
        while(frames.size()>120||playerBytes>playerLimit) { Frame first=frames.removeFirst();playerBytes-=first.bytes;total-=first.bytes; }
        while(total>globalLimit&&!players.isEmpty()) { UUID oldest=players.keySet().iterator().next();forget(oldest); }
    }
    public String json(UUID id) {
        var frames=players.get(id);if(frames==null)return "[]";
        return "["+String.join(",",frames.stream().sorted(Comparator.comparingLong(Frame::time)).map(Frame::json).toList())+"]";
    }
    public void forget(UUID id) { var frames=players.remove(id);lastTimes.remove(id);if(frames!=null)total-=frames.stream().mapToInt(Frame::bytes).sum(); }
    public int bytes() { return total; }
    public int players() { return players.size(); }
    public void clear() { players.clear();lastTimes.clear();total=0; }
    public static String sampleJson(Sample s) {
        StringBuilder b=new StringBuilder("{\"time\":").append(s.timeMillis()).append(",\"tick\":").append(s.tick())
            .append(",\"kind\":").append(quote(s.kind().name())).append(",\"measurements\":{");
        boolean first=true;
        for(var e:new TreeMap<>(s.measurements()).entrySet()) {
            if(!first)b.append(',');first=false;b.append(quote(e.getKey())).append(':');
            b.append(Double.isFinite(e.getValue())?e.getValue():quote(e.getValue().toString()));
        }
        b.append("},\"flags\":[");first=true;
        for(String flag:new TreeSet<>(s.flags())) { if(!first)b.append(',');first=false;b.append(quote(flag)); }
        return b.append("]}").toString();
    }
    public static String quote(String text) {
        StringBuilder b=new StringBuilder("\"");
        for(int i=0;i<text.length();i++) { char c=text.charAt(i);switch(c) {
            case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\"); case '\n' -> b.append("\\n");
            case '\r' -> b.append("\\r");case '\t' -> b.append("\\t");
            default -> { if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c); }
        }}return b.append('"').toString();
    }
}

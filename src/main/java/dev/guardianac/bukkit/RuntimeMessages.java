/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.bukkit;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable UTF-8 message catalogs; safe for asynchronous admission and storage callbacks. */
public final class RuntimeMessages {
    private RuntimeMessages() {}
    public enum Key {
        PACKET_DEFENSE(2),
        FINDING_ALERT(3),
        SEVERE_ANOMALY(2),
        BAN_BROADCAST_PERMANENT(1),
        BAN_BROADCAST_TEMPORARY(1),
        BAN_STAFF(3),
        BAN_DISCORD(4),
        BAN_DURATION_PERMANENT(0),
        BAN_DURATION_UNTIL(1),
        BAN_SCREEN(2),
        BAN_SCREEN_PERMANENT(0),
        BAN_SCREEN_TEMPORARY(1),
        LOGIN_INITIALIZING(0),
        LOGIN_RATE_LIMIT(0),
        CONNECTION_FLOOD(0),
        LOGIN_BUSY(0),
        RESTRICTED_IP_ADVISORY(1),
        CONFIRM_EXPIRED(0),
        UNBAN_FAILED(0),
        UNBAN_RECORDED(1),
        UNBAN_PARTIAL(1),
        UNBAN_NATIVE_INCOMPLETE(0),
        CONSOLE_USAGE(0),
        MANAGEMENT_WAIT(0),
        CONFIG_LOADED(0),
        CONFIG_REJECTED(0),
        INVALID_IP(0),
        INVALID_PLAYER(0),
        BAN_LOOKUP_FAILED(0),
        NO_ACTIVE_BAN(0),
        PROTECTED_IP_BANS(0),
        CONFIRM_PROMPT(1),
        COMMAND_USAGE(0),
        INVALID_CASE(0),
        EXPORT_WAIT(0),
        EVIDENCE_UNAVAILABLE(0),
        EVIDENCE_EXPORTED(1),
        EVIDENCE_EXPORT_FAILED(0),
        PERMISSION_DENIED(0),
        STATUS_SUPPORTED(0),
        STATUS_UNSUPPORTED(0),
        SYSTEM_ERROR(1),
        ERROR_UNKNOWN(0),
        ERROR_EVIDENCE_MAINTENANCE(0),
        ERROR_REVIEW_EXPIRY(0),
        ERROR_BACKUP(0),
        ERROR_EVIDENCE_SAVE(0),
        ERROR_EVIDENCE_SERIALIZE(0),
        ERROR_BAN_SAVE(0),
        ERROR_RESTRICTED_ADVISORY(0),
        ERROR_IP_ASSOCIATION(0),
        ERROR_COMMITTED_RECONCILIATION(0),
        ERROR_NATIVE_QUEUE(0),
        ERROR_NATIVE_SCAN(0),
        ERROR_NATIVE_READ(0),
        ERROR_NATIVE_BUSY(0),
        ERROR_NATIVE_CHANGED(0),
        ERROR_NATIVE_FOLLOW_CHANGED(0),
        ERROR_NATIVE_FOLLOW_FAILED(0),
        ERROR_NATIVE_OPERATION(0);
        private final int arguments;
        Key(int arguments){this.arguments=arguments;}
        public int arguments(){return arguments;}
    }
    private static final Pattern PLACEHOLDER=Pattern.compile("\\{([0-9]+)\\}");
    private static final Map<Key,String> JAPANESE=load("ja"),ENGLISH=load("en");
    /** The diagnostic allowlist never forwards unrecognized exception, address or secret text. */
    private static final Map<String,Key> DIAGNOSTICS=diagnosticKeys();

    public static String text(String language,Key key,Object... values) {
        Objects.requireNonNull(key);Objects.requireNonNull(values);
        if(values.length!=key.arguments())throw new IllegalArgumentException("Invalid runtime message argument count: "+key);
        Matcher matcher=PLACEHOLDER.matcher(catalog(language).get(key));StringBuilder result=new StringBuilder();
        while(matcher.find())matcher.appendReplacement(result,Matcher.quoteReplacement(String.valueOf(values[Integer.parseInt(matcher.group(1))])));
        return matcher.appendTail(result).toString();
    }
    /** Preserve machine metrics exactly; Japanese guidance is a separate preceding line. */
    public static String status(String language,String machineFields,boolean supportedRuntime) {
        Objects.requireNonNull(machineFields);
        return "en".equals(language)?machineFields:text(language,supportedRuntime?Key.STATUS_SUPPORTED:Key.STATUS_UNSUPPORTED)+"\n"+machineFields;
    }
    /** One captured language is used for both the explanation and duration during async admission. */
    public static String banScreen(String language,boolean permanent,java.time.Instant expiresAt,String caseId) {
        String duration=permanent?text(language,Key.BAN_SCREEN_PERMANENT):text(language,Key.BAN_SCREEN_TEMPORARY,expiresAt);
        return text(language,Key.BAN_SCREEN,duration,caseId);
    }
    public static String systemError(String language,String diagnostic) {
        Key key=diagnostic==null?Key.ERROR_UNKNOWN:DIAGNOSTICS.getOrDefault(diagnostic,Key.ERROR_UNKNOWN);
        return text(language,Key.SYSTEM_ERROR,text(language,key));
    }
    static Map<Key,String> catalog(String language){return "en".equals(language)?ENGLISH:JAPANESE;}
    static Map<String,Key> diagnosticKeys() {
        Map<String,Key> result=new java.util.HashMap<>();
        for(Key key:Key.values())if(key.name().startsWith("ERROR_")&&key!=Key.ERROR_UNKNOWN)result.put(ENGLISH.get(key),key);
        return Map.copyOf(result);
    }
    private static Map<Key,String> load(String language) {
        String path="/locales/runtime_"+language+".properties";Properties properties=new Properties();
        try(var stream=RuntimeMessages.class.getResourceAsStream(path)) {
            if(stream==null)throw new IllegalStateException("Missing runtime message catalog: "+language);
            properties.load(new InputStreamReader(stream,StandardCharsets.UTF_8));
        } catch(IOException error){throw new IllegalStateException("Unreadable runtime message catalog: "+language,error);}
        Map<Key,String> result=new EnumMap<>(Key.class);
        if(properties.size()!=Key.values().length)throw new IllegalStateException("Incomplete runtime message catalog: "+language);
        for(Key key:Key.values()) {
            String template=properties.getProperty(key.name());
            if(template==null||template.isBlank())throw new IllegalStateException("Missing runtime message: "+key);
            Set<Integer> indexes=new HashSet<>();Matcher matcher=PLACEHOLDER.matcher(template);
            while(matcher.find())indexes.add(Integer.parseInt(matcher.group(1)));
            if(indexes.size()!=key.arguments())throw new IllegalStateException("Invalid runtime message placeholders: "+key);
            for(int index=0;index<key.arguments();index++)if(!indexes.contains(index))throw new IllegalStateException("Invalid runtime message index: "+key);
            if(PLACEHOLDER.matcher(template).replaceAll("").matches("(?s).*[{}].*"))throw new IllegalStateException("Invalid runtime message braces: "+key);
            result.put(key,template);
        }
        return Map.copyOf(result);
    }
}

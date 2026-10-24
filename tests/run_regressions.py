"""Host regression tests of production pipeline/settings/cache. JNI/Android are controlled fakes.
Usage: python tests/run_regressions.py --jdk /path/to/jdk --gson /path/to/gson.jar --out /tmp/tests
This does not replace device tests of MediaPlayer, WindowManager, or real ASR.
"""
from pathlib import Path
import argparse, subprocess
a=argparse.ArgumentParser(); a.add_argument('--jdk',required=True); a.add_argument('--gson',required=True); a.add_argument('--out',required=True); args=a.parse_args()
out=Path(args.out).resolve(); src=out/'src'; src.mkdir(parents=True,exist_ok=True)
files={
'android/content/SharedPreferences.java': '''package android.content; import java.util.*;
public class SharedPreferences {
final Map<String,Object> map=new HashMap<>();
public boolean getBoolean(String k,boolean d){return (boolean)map.getOrDefault(k,d);}
public int getInt(String k,int d){return (int)map.getOrDefault(k,d);}
public long getLong(String k,long d){return (long)map.getOrDefault(k,d);}
public String getString(String k,String d){return (String)map.getOrDefault(k,d);}
@SuppressWarnings("unchecked") public Set<String> getStringSet(String k,Set<String>d){return (Set<String>)map.getOrDefault(k,d);}
public Editor edit(){return new Editor();} public class Editor {
public Editor putBoolean(String k,boolean v){map.put(k,v);return this;} public Editor putString(String k,String v){map.put(k,v);return this;}
public Editor putInt(String k,int v){map.put(k,v);return this;} public Editor putLong(String k,long v){map.put(k,v);return this;}
public Editor putStringSet(String k,Set<String>v){map.put(k,v);return this;} public Editor remove(String k){map.remove(k);return this;} public void apply(){}
}}''',
'android/content/Context.java': '''package android.content; import java.io.*; import java.util.*;
public class Context {public static final int MODE_PRIVATE=0; final File dir; final Map<String,SharedPreferences> prefs=new HashMap<>();
public Context(File d){dir=d;d.mkdirs();} public Context getApplicationContext(){return this;} public File getFilesDir(){return dir;}
public SharedPreferences getSharedPreferences(String n,int mode){return prefs.computeIfAbsent(n,k->new SharedPreferences());}}''',
'android/net/Uri.java':'package android.net; public class Uri { public final String value; private Uri(String s){value=s;} public static Uri parse(String s){return new Uri(s);} }',
'android/os/Looper.java':'package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}',
'android/os/Handler.java':'''package android.os; import java.util.concurrent.*; public class Handler {
static final ConcurrentLinkedQueue<Runnable> q=new ConcurrentLinkedQueue<>(); public Handler(Looper l){} public boolean post(Runnable r){q.add(r);return true;}
public static void drain(){Runnable r;while((r=q.poll())!=null)r.run();}}''',
'android/os/SystemClock.java':'package android.os; public class SystemClock {public static long elapsedRealtime(){return System.nanoTime()/1000000;}}',
'android/util/Log.java':'package android.util; public class Log {public static int w(String t,String m){return 0;} public static int w(String t,String m,Throwable e){return 0;} public static int i(String t,String m){return 0;}}',
'android/util/AtomicFile.java':'''package android.util; import java.io.*; import java.nio.file.*; public class AtomicFile {
public static volatile boolean fail; final File f; final File tmp; public AtomicFile(File f){this.f=f;tmp=new File(f+".new");}
public byte[] readFully()throws IOException{return Files.readAllBytes(f.toPath());}
public FileOutputStream startWrite()throws IOException{if(fail)throw new IOException("injected disk failure");return new FileOutputStream(tmp);}
public void finishWrite(FileOutputStream o)throws IOException{o.close();Files.move(tmp.toPath(),f.toPath(),StandardCopyOption.REPLACE_EXISTING);}
public void failWrite(FileOutputStream o){try{if(o!=null)o.close();tmp.delete();}catch(IOException ignored){}}}''',
'org/json/JSONObject.java':'''package org.json; import com.google.gson.*; public class JSONObject {
final JsonObject o; public JSONObject(){o=new JsonObject();} public JSONObject(String s){o=JsonParser.parseString(s).getAsJsonObject();} JSONObject(JsonObject o){this.o=o;}
public JSONObject put(String k,Object v){o.add(k,v instanceof JSONArray?((JSONArray)v).a:new Gson().toJsonTree(v));return this;}
public int getInt(String k){return o.get(k).getAsInt();} public long optLong(String k){return o.has(k)?o.get(k).getAsLong():0;}
public String optString(String k){return o.has(k)?o.get(k).getAsString():"";} public boolean optBoolean(String k){return o.has(k)&&o.get(k).getAsBoolean();}
public JSONArray optJSONArray(String k){return o.has(k)&&o.get(k).isJsonArray()?new JSONArray(o.getAsJsonArray(k)):null;} public String toString(){return o.toString();}}''',
'org/json/JSONArray.java':'''package org.json; import com.google.gson.*; public class JSONArray {
final JsonArray a; public JSONArray(){a=new JsonArray();} JSONArray(JsonArray a){this.a=a;} public int length(){return a.size();}
public JSONArray put(Object v){a.add(v instanceof JSONObject?((JSONObject)v).o:new Gson().toJsonTree(v));return this;}
public int getInt(int i){return a.get(i).getAsInt();} public JSONObject getJSONObject(int i){return new JSONObject(a.get(i).getAsJsonObject());}}''',
'com/yinsheng/player/AudioPcmDecoder.java':'''package com.yinsheng.player; import android.content.*;import android.net.*;import java.util.concurrent.*;
public class AudioPcmDecoder {static volatile CountDownLatch entered,unblock; static volatile String block=""; static volatile boolean fail;
public static float[] decodeRange(Context c,Uri u,long s,long e){if(fail)return null;if(u.value.equals(block)){entered.countDown();try{unblock.await();}catch(Exception ex){throw new RuntimeException(ex);}}return new float[16000];}}''',
'com/yinsheng/player/SpeechEngine.java':'''package com.yinsheng.player; import android.content.*;import java.util.*;import java.util.concurrent.*;
public class SpeechEngine {static volatile CountDownLatch entered,unblock;static volatile boolean hold,released; public SpeechEngine(Context c,String t,String...paths){}
public static class SpeechSeg{public long startMs,endMs;public String text;} public static class AsrResult{public boolean hasSpeech;public List<SpeechSeg>segments=new ArrayList<>();}
public AsrResult recognize(float[]pcm,long s){if(hold){entered.countDown();try{unblock.await();}catch(Exception ex){throw new RuntimeException(ex);}}AsrResult r=new AsrResult();SpeechSeg x=new SpeechSeg();x.text="今日は学校に行きます";x.startMs=s;x.endMs=s+1000;r.hasSpeech=true;r.segments.add(x);return r;}
public void release(){released=true;}}''',
'com/yinsheng/player/ModelManager.java':'''package com.yinsheng.player;import android.content.*;public class ModelManager {
public static boolean ensureModels(Context c){return true;}public static boolean ensureWhisperModels(Context c){return true;}
public static String whisperEncoderPath(Context c){return "";}public static String whisperDecoderPath(Context c){return "";}public static String whisperTokensPath(Context c){return "";}public static String modelPath(Context c){return "";}public static String tokensPath(Context c){return "";}}''',
'com/yinsheng/player/TranslationEngine.java':'''package com.yinsheng.player;import android.content.*;import java.util.*;public class TranslationEngine {
public boolean isAvailable(){return true;}public List<String>translateBatch(List<String>s){return Collections.nCopies(s.size(),"今天去学校");}
public static class Factory{public static TranslationEngine create(Context c,AppSettings s){return new TranslationEngine();}}}''',
'com/yinsheng/player/CloudAsrEngine.java':'''package com.yinsheng.player;import java.util.*;public class CloudAsrEngine {
public static class Sentence{public long startMs,endMs;public String text;}public List<Sentence>recognize(byte[]a)throws Exception{throw new Exception("offline");}public static CloudAsrEngine create(AppSettings s){return new CloudAsrEngine();}}''',
'com/yinsheng/player/BulkGenerator.java':'package com.yinsheng.player;public class BulkGenerator {public static byte[]pcmToWav16k(float[]p){return new byte[0];}}',
}
for name,text in files.items():
    f=src/name; f.parent.mkdir(parents=True,exist_ok=True); f.write_text(text,encoding='utf-8')
root=Path(__file__).resolve().parents[1]
production=root/'app/src/main/java/com/yinsheng/player'
sources=[str(f) for f in src.rglob('*.java')]+[str(production/f'{n}.java') for n in ['SubtitlePipeline','SubtitleManager','SubtitleLanguage','AppSettings']]+[str(root/'tests/RegressionSuite.java')]
classes=out/'classes';classes.mkdir(exist_ok=True)
exe='.exe' if (Path(args.jdk)/'bin/javac.exe').exists() else ''
subprocess.run([str(Path(args.jdk)/f'bin/javac{exe}'),'-encoding','UTF-8','-cp',args.gson,'-d',str(classes)]+sources,check=True)
import os
subprocess.run([str(Path(args.jdk)/f'bin/java{exe}'),'-cp',str(classes)+os.pathsep+args.gson,'com.yinsheng.player.RegressionSuite',str(out/'data')],check=True)

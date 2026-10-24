package com.yinsheng.player;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.util.AtomicFile;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

public class RegressionSuite {
    static int tests;
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    static void pass(String s){System.out.println("PASS "+s);tests++;}
    static void await(BooleanSupplier s)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!s.getAsBoolean()&&System.nanoTime()<end){Handler.drain();Thread.sleep(10);}Handler.drain();check(s.getAsBoolean(),"timeout");}
    static SubtitlePipeline.AudioInfo info(String key){var i=new SubtitlePipeline.AudioInfo();i.audioKey=key;i.uri=Uri.parse(key);i.durationMs=60000;i.totalSegs=1;return i;}
    static List<SubtitleManager.SubtitleLine> lines(String text){return new ArrayList<>(List.of(new SubtitleManager.SubtitleLine(0,1000,text,"",false)));}
    static class Listener implements SubtitlePipeline.Listener {
        int done;public void onSegmentStarted(int i){} public void onProgress(int i,int p){} public void onSegmentDone(int i,boolean n){done++;}
        public void onAllDone(){} public void onModelPreparing(String s){} public void onModelMissing(){} public void onEngineError(String s){} public void onInfo(String s){}
    }
    public static void main(String[]args)throws Exception{
        Context c=new Context(new File(args[0],"run-"+System.nanoTime()));
        for(String s:List.of("今日は学校に行きます","今日は天気が良いですね","明日も一緒に散歩しましょう","東京","ﾃｽﾄ"))check(!SubtitleLanguage.isChinese(s),s);
        check(SubtitleLanguage.isChinese("这是一段足够长的中文句子用于检查中文判断功能"),"Chinese");pass("Japanese and ambiguous Han phrases are translated");
        AppSettings settings=new AppSettings(c);
        check(!settings.isFloatingSubtitlesEnabled(),"overlay default");settings.setFloatingSubtitlesEnabled(true);settings.setFloatingSubtitlesEnabled(false);check(!settings.isFloatingSubtitlesEnabled(),"overlay off persists");pass("Overlay defaults off and explicit disable persists");
        settings.setTransEngine("zhipu");check(!settings.isTranslationConfigured(),"empty");settings.setZhipuKey("test");check(settings.isTranslationConfigured(),"zhipu");
        settings.setTransEngine("alibaba");settings.setAlibabaKey("test");check(settings.isTranslationConfigured(),"alibaba");
        settings.setTransEngine("deepseek");settings.setDeepseekKey("test");check(settings.isTranslationConfigured(),"deepseek");
        settings.setTransEngine("baidu");settings.setBaiduKey("test");check(!settings.isTranslationConfigured(),"baidu needs ID");settings.setBaiduAppid("test");check(settings.isTranslationConfigured(),"baidu");pass("All four translation providers validate independently");
        SubtitleManager cache=new SubtitleManager(c);
        ExecutorService writers=Executors.newFixedThreadPool(2);
        Future<?> f1=writers.submit(()->cache.saveSegment("parallel",0,lines("a"),false,"local"));
        Future<?> f2=writers.submit(()->new SubtitleManager(c).saveSegment("parallel",1,lines("b"),false,"cloud"));
        f1.get();f2.get();writers.shutdown();check(cache.isAllDone("parallel",2),"lost committed segment");pass("Concurrent segment writes retain both commits");
        cache.mergeFullSrt("parallel",2);check(cache.hasFullCache("parallel"),"full cache");
        var translated=cache.loadSegment("parallel",0);translated.get(0).translated="译文";cache.saveSegment("parallel",0,translated,false,"local");
        check(cache.loadFullCache("parallel").get(0).translated.equals("译文"),"stale full.srt");cache.mergeFullSrt("parallel",2);pass("Backfill survives reload and full cache regeneration");
        AtomicFile.fail=true;boolean threw=false;try{cache.saveSegment("failure",0,lines("a"),false,"local");}catch(IllegalStateException e){threw=true;}finally{AtomicFile.fail=false;}
        check(threw&&cache.getProcessedSegments("failure").isEmpty(),"false completion");pass("Disk write failure is propagated without completion");
        Files.writeString(new File(c.getFilesDir(),"subtitles/parallel/seg_1.json").toPath(),"{broken");check(!cache.isAllDone("parallel",2),"corruption trusted");pass("Corrupt cache is excluded from completed segments");
        SubtitlePipeline pipeline=new SubtitlePipeline(c,cache,settings);Listener listener=new Listener();pipeline.setListener(listener);
        AudioPcmDecoder.block="A";AudioPcmDecoder.entered=new CountDownLatch(1);AudioPcmDecoder.unblock=new CountDownLatch(1);
        pipeline.start(info("A"),0);check(AudioPcmDecoder.entered.await(3,TimeUnit.SECONDS),"A did not enter");pipeline.start(info("B"),0);AudioPcmDecoder.unblock.countDown();
        await(()->cache.isAllDone("B",1));Handler.drain();check(cache.getProcessedSegments("A").isEmpty(),"old song committed");check(listener.done==1,"old song callback");pipeline.shutdown();pass("Switching song rejects stale result and callback");
        AudioPcmDecoder.block="";AudioPcmDecoder.fail=true;
        SubtitlePipeline failing=new SubtitlePipeline(c,cache,settings);failing.start(info("decode-failure"),0);Thread.sleep(150);check(cache.getProcessedSegments("decode-failure").isEmpty(),"decode failure cached as silence");failing.shutdown();AudioPcmDecoder.fail=false;pass("Decode failure remains retryable, not silence");
        Thread.sleep(100);SpeechEngine.hold=true;SpeechEngine.released=false;SpeechEngine.entered=new CountDownLatch(1);SpeechEngine.unblock=new CountDownLatch(1);
        SubtitlePipeline closing=new SubtitlePipeline(c,cache,settings);closing.start(info("closing"),0);check(SpeechEngine.entered.await(3,TimeUnit.SECONDS),"native recognition did not enter");
        closing.shutdown();Thread.sleep(100);check(!SpeechEngine.released,"released while recognition active");SpeechEngine.unblock.countDown();await(()->SpeechEngine.released);SpeechEngine.hold=false;pass("Native release waits until active recognition returns");
        System.out.println("PASSED "+tests+" regression scenarios");
    }
}

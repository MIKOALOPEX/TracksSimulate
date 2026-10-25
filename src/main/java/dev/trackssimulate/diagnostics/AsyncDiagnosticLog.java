package dev.trackssimulate.diagnostics;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Only detached snapshots may be submitted. No world access or disk I/O on the producer. */
public final class AsyncDiagnosticLog implements AutoCloseable {
    private final Path file;
    private final long fileLimit;
    private final int archives;
    private final ArrayBlockingQueue<Supplier<String>> queue;
    private final Consumer<String> notice;
    private final AtomicLong dropped=new AtomicLong();
    private volatile boolean stopping;
    private volatile Thread worker;
    private boolean failed;

    public AsyncDiagnosticLog(Path file,int capacity,long fileLimit,int archives,Consumer<String> notice) {
        if(capacity<1||fileLimit<1||archives<1)throw new IllegalArgumentException("Invalid diagnostic bounds");
        this.file=file;this.queue=new ArrayBlockingQueue<>(capacity);this.fileLimit=fileLimit;
        this.archives=archives;this.notice=notice;
    }
    public synchronized void submit(Supplier<String> snapshot) {
        if(failed||(stopping&&worker!=null)){dropped.incrementAndGet();return;}
        if(worker==null) {
            stopping=false;
            worker=new Thread(this::run,"Tracks-Physics-Log");worker.setDaemon(true);worker.start();
        }
        if(!queue.offer(snapshot)) {
            if(queue.poll()!=null)dropped.incrementAndGet();
            if(!queue.offer(snapshot))dropped.incrementAndGet();
        }
    }
    public long dropped(){return dropped.get();}
    public int queued(){return queue.size();}
    public boolean active(){return worker!=null;}
    /** Never join a writer that may be blocked in external I/O on the server thread. */
    @Override public synchronized void close() {
        stopping=true;failed=false;
        dropped.addAndGet(queue.size());queue.clear();
        // Wake an idle writer without interrupting an in-flight file write.
        if(worker!=null)queue.offer(()->"");
    }
    private void rotate() throws IOException {
        Files.deleteIfExists(file.resolveSibling(file.getFileName()+"."+archives));
        for(int i=archives-1;i>=1;i--) {
            Path from=file.resolveSibling(file.getFileName()+"."+i);
            if(Files.exists(from))Files.move(from,file.resolveSibling(file.getFileName()+"."+(i+1)),StandardCopyOption.REPLACE_EXISTING);
        }
        if(Files.exists(file))Files.move(file,file.resolveSibling(file.getFileName()+".1"),StandardCopyOption.REPLACE_EXISTING);
    }
    private void run() {
        BufferedOutputStream output=null;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            long size=Files.exists(file)?Files.size(file):0;
            output=new BufferedOutputStream(Files.newOutputStream(file,StandardOpenOption.CREATE,StandardOpenOption.APPEND));
            long written=0,lastReport=System.nanoTime(),formatNanos=0,writeNanos=0;
            notice.accept("[TS-DIAG] detailed physics log="+file+" queueCapacity="+(queue.size()+queue.remainingCapacity())+" fileLimit="+fileLimit+" archives="+archives+" droppedTotal="+dropped.get());
            while(!stopping) {
                Supplier<String> snapshot=queue.take();
                if(stopping)break;
                long begin=System.nanoTime();
                byte[] data=(snapshot.get()+System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
                formatNanos+=System.nanoTime()-begin;
                // Even an accidentally oversized report cannot defeat the disk bound.
                if(data.length>fileLimit){dropped.incrementAndGet();continue;}
                begin=System.nanoTime();
                if(size+data.length>fileLimit){output.close();rotate();output=new BufferedOutputStream(Files.newOutputStream(file));size=0;}
                output.write(data);output.flush();size+=data.length;written++;
                writeNanos+=System.nanoTime()-begin;
                if(System.nanoTime()-lastReport>=30_000_000_000L) {
                    notice.accept("[TS-DIAG] records="+written+" queued="+queue.size()+" droppedTotal="+dropped.get()+" formatMs="+formatNanos/1e6+" writeMs="+writeNanos/1e6+" (background writer)");
                    written=formatNanos=writeNanos=0;lastReport=System.nanoTime();
                }
            }
        } catch(InterruptedException ignored) {
            // Server stop: discard pending routine snapshots; never delay shutdown.
        } catch(Exception e) {
            synchronized(this){failed=true;}
            notice.accept("[TS-DIAG] writer failed; disabled until server restart: "+e);
        } finally {
            if(output!=null)try{output.close();}catch(IOException ignored){}
            synchronized(this){if(!stopping)dropped.addAndGet(queue.size());queue.clear();worker=null;}
        }
    }
}

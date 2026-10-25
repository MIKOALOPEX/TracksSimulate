import dev.trackssimulate.diagnostics.AsyncDiagnosticLog;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real writer/queue/file lifecycle checks, not a game performance benchmark. */
public final class DiagnosticLogChecks {
    private static int checks;
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static void stopped(AsyncDiagnosticLog log)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(log.active()&&System.nanoTime()<deadline)Thread.sleep(1);
        check(!log.active(),"worker stops without retaining a server");
    }
    public static void main(String[] args)throws Exception {
        Path directory=Files.createTempDirectory("tracks-diagnostic-check-");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var last=new CountDownLatch(1);var caller=new AtomicReference<String>();
        var notices=new CopyOnWriteArrayList<String>();
        var log=new AsyncDiagnosticLog(directory.resolve("physics.log"),2,300,2,notices::add);
        try {
            log.submit(()->{caller.set(Thread.currentThread().getName());entered.countDown();
                try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
                return "first";});
            check(entered.await(5,TimeUnit.SECONDS),"background formatter starts");
            var producer=Executors.newSingleThreadExecutor();
            try {
                producer.submit(()->{for(int i=0;i<10;i++){final int n=i;log.submit(()->{if(n==9)last.countDown();return "sample-"+n;});}}).get(2,TimeUnit.SECONDS);
                check(log.queued()==2,"queue capacity remains bounded during a blocked consumer");
                check(log.dropped()==8,"oldest snapshots dropped without blocking producer");
            } finally {producer.shutdownNow();release.countDown();}
            check(last.await(5,TimeUnit.SECONDS),"newest snapshot survives overflow");
            log.close();stopped(log);
            String saved=Files.readString(directory.resolve("physics.log"));
            check(saved.contains("sample-9")&&!saved.contains("sample-0"),"retained snapshot is actually written");
            check("Tracks-Physics-Log".equals(caller.get()),"formatting executes only on writer thread");
            // Restart the same sink after server stop. Exercise real file rotation.
            for(int i=0;i<8;i++) {
                var rendered=new CountDownLatch(1);
                log.submit(()->{rendered.countDown();return "x".repeat(200);});
                check(rendered.await(5,TimeUnit.SECONDS),"restart formats next snapshot");
                log.close();stopped(log);
            }
            try(var files=Files.list(directory)) {
                var paths=files.toList();check(paths.size()<=3,"active file plus bounded archives");
                for(var path:paths)check(Files.size(path)<=300,"rotation respects byte limit");
            }
            Path invalid=directory.resolve("not-a-directory");Files.writeString(invalid,"file");
            var broken=new AsyncDiagnosticLog(invalid.resolve("physics.log"),2,300,2,notices::add);
            broken.submit(()->"never written");stopped(broken);
            check(notices.stream().anyMatch(s->s.contains("writer failed")),"I/O failure reported on worker");
            broken.submit(()->"discarded");check(!broken.active()&&broken.dropped()>0,"failed I/O does not restart every physics sample");
            broken.close();
        } finally {
            release.countDown();log.close();stopped(log);
            // Only files created in this dedicated temporary directory.
            try(var files=Files.list(directory)){for(var file:files.toList())Files.deleteIfExists(file);}
            Files.deleteIfExists(directory);
        }
        System.out.println("Diagnostic writer checks passed: "+checks+". Queue/lifecycle/files only; no gameplay performance claims.");
    }
}

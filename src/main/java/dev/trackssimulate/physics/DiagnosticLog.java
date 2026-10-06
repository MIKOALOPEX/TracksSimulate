package dev.trackssimulate.physics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Bounded, append-only diagnostics, independent of Minecraft so rotation can be checked offline. */
public final class DiagnosticLog {
    private final Path path;private final long maxBytes;private final int archives;
    public DiagnosticLog(Path path,long maxBytes,int archives) {this.path=path;this.maxBytes=maxBytes;this.archives=archives;}
    public synchronized void append(String record) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        byte[] bytes=(record+System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        if(Files.exists(path)&&Files.size(path)+bytes.length>maxBytes) {
            for(int i=archives;i>=1;i--) {
                Path source=i==1?path:archive(i-1),target=archive(i);
                if(Files.exists(source))Files.move(source,target,StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.write(path,bytes,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    private Path archive(int index) {return path.resolveSibling(path.getFileName()+"."+index);}
}

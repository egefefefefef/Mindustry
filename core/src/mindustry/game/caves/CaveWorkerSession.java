package mindustry.game.caves;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static mindustry.Vars.*;

/** Desktop process and its isolated temporary data. No shell or user-supplied command is used. */
public final class CaveWorkerSession implements AutoCloseable{
    private final Path directory;
    private final CavePersistentClient client;

    private CaveWorkerSession(Path directory, CavePersistentClient client){
        this.directory = directory;
        this.client = client;
    }

    public static CaveWorkerSession launch(byte[] snapshot, String[] names, int team) throws IOException{
        if(snapshot == null || snapshot.length == 0 || snapshot.length > CaveArchive.maxSnapshotBytes){
            throw new IllegalArgumentException("Invalid cave snapshot");
        }
        String classpath = System.getProperty("java.class.path");
        if(javaPath == null || javaPath.isEmpty() || classpath == null || classpath.isEmpty()){
            throw new IOException("Java runtime or classpath unavailable for cave worker");
        }
        Path directory = Files.createTempDirectory("mindustry-cave-");
        try{
            Path input = directory.resolve("cave.msav");
            Files.write(input, snapshot, StandardOpenOption.CREATE_NEW);
            var command = List.of(javaPath, "-cp", classpath, "mindustry.server.CavePersistentService",
                input.toString(), directory.resolve("data").toString());
            var client = CavePersistentClient.launch(command, directory.resolve("worker.log"), input, names, team, 15000);
            return new CaveWorkerSession(directory, client);
        }catch(IOException | RuntimeException error){
            deleteTemporary(directory);
            throw error;
        }
    }

    public CavePersistentClient client(){ return client; }

    @Override public void close(){
        client.close();
        deleteTemporary(directory);
    }

    private static void deleteTemporary(Path directory){
        try(Stream<Path> entries = Files.walk(directory)){
            entries.sorted(Comparator.reverseOrder()).forEach(path -> {
                try{ Files.deleteIfExists(path); }catch(IOException ignored){}
            });
        }catch(IOException ignored){}
    }
}

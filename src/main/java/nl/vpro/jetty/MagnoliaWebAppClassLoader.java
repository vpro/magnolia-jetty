package nl.vpro.jetty;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;

/**
 * See MGNL-12866
 * We just override the jetty class loader to straight-forwarding look on the file system if a resource exists.
 *
 * For now no optimization (perhaps cache for all this would be imaginable) whatsoever was done. It seems not very necessary.
 *
 * It will watch all {@literal ../*}/src/main/resources/ directories.
 *
 * @author Michiel Meeuwissen
 * @since 3.1
 */
public class MagnoliaWebAppClassLoader extends WebAppClassLoader {

    private static final Logger LOG = LoggerFactory.getLogger(MagnoliaWebAppClassLoader.class);

    private static final String RESOURCES = "src" + File.separator + "main" + File.separator + "resources" + File.separator;

    private final File[] dirs;

    private final Map<String, Resource> found = new ConcurrentHashMap<>();
    private final WatchService watchService = FileSystems.getDefault().newWatchService();


    {
        File dir = new File(System.getProperty("user.dir")).getParentFile();
        dirs = Arrays.stream(Objects.requireNonNull(dir.listFiles(pathname -> pathname.isDirectory() && new File(pathname, RESOURCES).isDirectory()))).map(f -> new File(f, RESOURCES)).toArray(File[]::new);
        if (dirs.length > 0) {
            LOG.info("Watching files in {}", Arrays.asList(dirs));
        } else {
            LOG.info("Could not find files to watch");
        }
        if (dirs.length > 0) {
             new Thread(this::watch, "Watching directories for jetty run").start();
        }
    }

    public MagnoliaWebAppClassLoader(Context context) throws IOException {
        super(context);
    }

    public MagnoliaWebAppClassLoader(ClassLoader parent, Context context) throws IOException {
        super(parent, context);
    }

    @Override
    public URL getResource(String fileName) {
        if (! fileName.endsWith(".class")) { // no chance on that
            for (File file : dirs) {
                final File resource = new File(file, fileName);
                if (resource.canRead()) {
                    Resource f = found.computeIfAbsent(fileName, (fn) -> {
                        LOG.info("Found {} -> {}", fileName, resource);
                        return new Resource(resource);
                    });
                    if (! f.file.equals(resource)) {
                        LOG.info("Updated {} ({} -> {})", fileName, f.file, resource);
                        f.file = resource;
                        f.lastModified = resource.lastModified();
                    }
                    if (f.lastModified < resource.lastModified()) {
                        LOG.info("Updated {} ({} -> {})", resource, Instant.ofEpochMilli(f.lastModified), Instant.ofEpochMilli(resource.lastModified()));
                        f.lastModified = resource.lastModified();
                    }

                    try {
                        LOG.debug("Found {}", resource);
                        return resource.toURI().toURL();
                    } catch (MalformedURLException e) {
                        LOG.error("{} -> {}", resource, e.getMessage());

                    }
                } else {
                    LOG.debug("Cannot read {}", resource);
                }
            }
            LOG.debug("Cannot read {}", fileName);
        }
        return super.getResource(fileName);
    }

    private URL[] urls;


    /**
     * See MGNL-18589
     */
    @Override
    public synchronized URL[] getURLs() {
        if (urls == null) {
            List<URL> result = new ArrayList<>();
            for (File resourceFile : dirs) {
                try {
                    URL url = resourceFile.toURI().toURL();
                    result.add(url);
                } catch (MalformedURLException e) {
                    LOG.error("{} -> {}", resourceFile, e.getMessage());
                }
            }
            result.addAll(Arrays.asList(super.getURLs()));

            urls =  result.toArray(new URL[0]);
        }
        return urls;
    }


    /**
     * Just represents one file with its original last modified timestamp.
     * We can use this to detect and log changes.
     */
    static class Resource {
        private File file;
        private long lastModified;

        Resource(File f) {
            file = f;
            lastModified =  f.lastModified();
        }
    }


    /**
     * Watch for all creation events, and if one happens, touch the containing directory.
     * This triggers info.magnolia.resourceloader.classpath.service.impl.devmode.DevelopmentModeClasspathService.reload
     *
     * Otherwise, it doesn't. It doesn't seem to be expecting directories, just jars.
     */
    protected  void watch() {

        final Map<WatchKey, Path> keys = registerWatchers();
        LOG.info("Watching {} directories for new files", keys.size());
        boolean valid = true;
        while (valid) {
            try {
                WatchKey key = watchService.take();
                final Path dir = keys.get(key);
                if (dir == null) {
                    continue;
                }
                for (WatchEvent<?> event : key.pollEvents()) {
                    Path context = dir.resolve((Path) event.context());
                    if (Files.isRegularFile(context)) {
                        for (File d : dirs) {
                            if (context.toAbsolutePath().startsWith(d.getAbsolutePath())) {
                                LOG.info("Found change in {}, touching {}", context, d);
                                long lastModified = System.currentTimeMillis();
                                if (d.lastModified() < lastModified) {
                                    boolean success = d.setLastModified(lastModified);
                                    if (!success) {
                                        LOG.warn("Could not set timestamp of {}", d);
                                    }
                                }
                            }
                        }
                    } else if (Files.isDirectory(context)) {
                        for (File d : dirs) {
                            if (context.toAbsolutePath().startsWith(d.getAbsolutePath())) {
                                LOG.info("Found new directory in {} in {}, watching too", context, d);
                                try {
                                    context.register(watchService, ENTRY_CREATE);
                                } catch (IOException e) {
                                    LOG.error(e.getMessage());
                                }
                                break;
                            }
                        }
                    }

                }
                valid = key.reset(); // IMPORTANT: The key must be reset after processed
            } catch (InterruptedException e) {
                LOG.info("Interrupted");
                return;
            }
        }
    }

    protected Map<WatchKey, Path>  registerWatchers()  {
        final Map<WatchKey, Path> keys = new HashMap<>();

         for (File watchedDirectory : dirs) {
            Path path = Paths.get(watchedDirectory.toString());
            try {
                Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        WatchKey key = dir.register(watchService, ENTRY_CREATE);
                        LOG.debug("Watching {}", dir.toAbsolutePath());
                        keys.put(key, dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                LOG.warn("For {}: {}", watchedDirectory, e.getMessage());
            }
         }
         return keys;
    }

}

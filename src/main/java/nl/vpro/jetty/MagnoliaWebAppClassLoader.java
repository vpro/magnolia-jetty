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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

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

    private final Set<File> dirs;

    private final Map<String, Resource> found = new ConcurrentHashMap<>();
    private final Map<String, Resource> jars = new ConcurrentHashMap<>();
    private final WatchService watchService = FileSystems.getDefault().newWatchService();



    private Pattern touchJars = Pattern.compile("^magnolia(?!\\-lang).*$");

    {
        final Set<File> parentDirs = new HashSet<>();

        String magnoliaHome = System.getProperty("magnolia.home");
        if (magnoliaHome != null) {
            LOG.info("Magnolia home detected {}", magnoliaHome);
            parentDirs.add(new File(magnoliaHome).getParentFile().getParentFile());
            parentDirs.add(new File(magnoliaHome).getParentFile());
        }
        String mavenMultiModule = System.getProperty("maven.multiModuleProjectDirectory");
        if (mavenMultiModule != null) {
            parentDirs.add(new File(mavenMultiModule).getParentFile());
            parentDirs.add(new File(mavenMultiModule));
        }
        String userDir = System.getProperty("user.dir");
        if (userDir != null && ! userDir.equals(mavenMultiModule)) {
            parentDirs.add(new File(userDir).getParentFile());
            parentDirs.add(new File(userDir));
        }
        dirs = parentDirs.stream().flatMap(dir ->
            Arrays.stream(
                Objects.requireNonNull(
                    dir.listFiles(pathname ->
                        pathname.isDirectory() && new File(pathname, RESOURCES).isDirectory()
                    )
                )
            ).map(f -> new File(f, RESOURCES))
        ).collect(Collectors.toSet());
        if (!dirs.isEmpty()) {
            LOG.info("Watching files in {}", dirs);
            new Thread(this::watch, "Watching directories for jetty run").start();
        } else {
            LOG.info("Could not find files to watch");
        }
    }

    public MagnoliaWebAppClassLoader(Context context) throws IOException {
        super(context);
    }

    public MagnoliaWebAppClassLoader(ClassLoader parent, Context context) throws IOException {
        super(parent, context);
    }

    public Pattern isTouchJars() {
        return touchJars;
    }

    public void setTouchJars(String touchJars) {
        if (touchJars == null || touchJars.trim().isEmpty()) {
            this.touchJars = null;
        } else {
            this.touchJars = Pattern.compile(touchJars);
        }
    }


    protected WatchEvent.Kind<?>[] getKinds() {
        if (touchJars != null) {
            return new WatchEvent.Kind[]{ENTRY_CREATE, ENTRY_MODIFY};
        } else {
            return new WatchEvent.Kind[]{ENTRY_CREATE};
        }
    }

    @Override
    public URL getResource(String fileName) {
        if (! fileName.endsWith(".class")) { // no chance on that
            for (File dir : dirs) {

                final File resource = new File(dir, fileName);
                if (resource.canRead()) {
                    Resource f = found.computeIfAbsent(fileName, (fn) -> {
                        LOG.debug("Found {} -> {}", fileName, resource);
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
        URL resource = super.getResource(fileName);
        if (touchJars != null) {
            if (resource != null && "jar".equals(resource.getProtocol())) {
                final String fileUrl = resource.getFile().split("!")[0];
                final File file = new File(fileUrl.substring("file:".length()));
                jars.computeIfAbsent(fileUrl, k -> {
                    if (touchJars.matcher(file.getName()).matches()) {
                        LOG.info("Found new (touchable) jar {}", file);
                    } else {
                        LOG.debug("Found new jar {}", file);
                    }
                    return new Resource(file);
                });
            }
        }
        return resource;
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
                try {
                    final Path dir = keys.get(key);
                    if (dir == null) {
                        LOG.info("No such dir {} watching. Ignoring event.", key);
                        continue;
                    }
                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path context = dir.resolve((Path) event.context());
                        if (Files.isRegularFile(context)) {
                            boolean found = false;
                            for (File d : dirs) {
                                if (context.toAbsolutePath().startsWith(d.getAbsolutePath())) {
                                    LOG.info("Found {} in {}, touching {}", event.kind(), context, d);
                                    found = true;
                                    touch(d);
                                    touchJars();
                                } else {
                                    LOG.debug("Ignoring {} (not in {})", context, d);
                                }
                            }
                            if (! found){
                                LOG.warn("Could not find anything to match {}", event);
                            }
                        } else if (Files.isDirectory(context) && event.kind() == ENTRY_CREATE) {
                            for (File d : dirs) {
                                if (context.toAbsolutePath().startsWith(d.getAbsolutePath())) {
                                    LOG.info("Found new directory {} in {} in {}, watching too", event.kind(), context, d);
                                    try {
                                        WatchKey newKey = context.register(watchService, getKinds());
                                        keys.put(newKey, context);
                                        LOG.info("Now watching {} directories for new files", keys.size());
                                        touch(d); // new directory may contain files
                                    } catch (IOException e) {
                                        LOG.error(e.getMessage());
                                    }
                                    break;
                                }
                            }
                        }

                    }
                } finally {
                    boolean keyValid = key.reset(); // IMPORTANT: The key must be reset after processed
                    if (! keyValid) {
                        LOG.info("Resetting the key {} was impossible because it is not valid. Stopping watch.", key);
                        keys.remove(key);
                    }
                }
            } catch (InterruptedException e) {
                LOG.info("Interrupted");
                return;
            }
        }
        LOG.info("Stopped watching.");
    }

    protected void touchJars() {
        if (touchJars != null) {
            int count = 0;
            for (Resource j : jars.values()) {
                if (touchJars.matcher(j.file.getName()).matches()) {
                    touch(j);
                    count++;
                }
            }
            if (count > 0) {
                LOG.info("Touched {}  {} jars too", count, touchJars.pattern());
            }
        }
    }
    protected void touch(Resource d) {
        touch(d.file);
        d.lastModified = d.file.lastModified();
    }


    protected void touch(File d) {
        long lastModified = System.currentTimeMillis();
        if (d.lastModified() < lastModified) {
            boolean success = d.setLastModified(lastModified);
            if (!success) {
                LOG.warn("Could not set timestamp of {}", d);
            }
        }
    }

    protected Map<WatchKey, Path>  registerWatchers()  {
        final Map<WatchKey, Path> dirKeys = new HashMap<>();

         for (File watchedDirectory : dirs) {
            Path path = Paths.get(watchedDirectory.toString());
            try {
                Files.walkFileTree(path, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        try {
                            WatchKey key = dir.register(watchService, getKinds());
                            LOG.debug("Watching {}", dir.toAbsolutePath());
                            dirKeys.put(key, dir);
                            if (dirKeys.size() % 200 == 0) {
                                LOG.info("Now watching {} directories for new files (still walking)", dirKeys.size());
                            }
                        } catch (Exception e) {
                            LOG.warn(e.getMessage());
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                LOG.warn("For {}: {}", watchedDirectory, e.getMessage());
            }
         }
         return dirKeys;
    }

}

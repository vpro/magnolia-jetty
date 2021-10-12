package nl.vpro.jetty;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * See MGNL-12866
 * We just override the jetty class loader to straight-forwarding look on the file system if a resource exists.
 *
 * For now no optimization (perhaps cache for all this would be imaginable) whatsoever was done. It seems not very necessary.
 *
 * @author Michiel Meeuwissen
 * @since 3.1
 */
public class MagnoliaWebAppClassLoader extends WebAppClassLoader {

    private static final Logger LOG = LoggerFactory.getLogger(MagnoliaWebAppClassLoader.class);

    private static final String RESOURCES = "src" + File.separator + "main" + File.separator + "resources" + File.separator;

    private final File[] dirs;

    private final Map<String, Resource> found = new ConcurrentHashMap<>();

    {
        File dir = new File(System.getProperty("user.dir")).getParentFile();
        dirs = dir.listFiles(pathname -> pathname.isDirectory() && new File(pathname, RESOURCES).isDirectory());
        if (dirs != null) {
            LOG.info("Watching files in {}", Arrays.asList(dirs));
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

    @Override
    public URL getResource(String name) {
        if (! name.endsWith(".class")) { // no chance on that
            String fileName = RESOURCES + name;
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
        return super.getResource(name);
    }

    private URL[] urls;


    /**
     * See MGNL-18589
     */
    @Override
    public synchronized URL[] getURLs() {
        if (urls == null) {
            List<URL> result = new ArrayList<>();
            for (File f : dirs) {
                try {
                    result.add(new File(f, RESOURCES).toURI().toURL());
                } catch (MalformedURLException e) {
                    LOG.warn(e.getMessage());
                }
            }
            result.addAll(Arrays.asList(super.getURLs()));

            urls =  result.toArray(new URL[0]);
        }
        return urls;
    }

    /**
     * Just represents one file with it's original lastmodified timestamp.
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
}

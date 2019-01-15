package nl.vpro.jetty;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

import org.eclipse.jetty.webapp.WebAppClassLoader;

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
    private static String RESOURCES = "src" + File.separator + "main" + File.separator + "resources" + File.separator;


    private final File[] dirs;

    {
        File dir = new File(System.getProperty("user.dir")).getParentFile();
        dirs = dir.listFiles(pathname -> pathname.isDirectory() && new File(pathname, RESOURCES).isDirectory());
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
                File resource = new File(file, fileName);
                if (resource.canRead()) {
                    try {
                        return resource.toURI().toURL();
                    } catch (MalformedURLException e) {

                    }
                }
            }
        }
        return super.getResource(name);
    }

}

package pl.matsuo.navi;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Copies trips into a folder on the unit, under {@code files/export/<year>/}.
 *
 * <p>No account, no network, no permission: it works on a head unit that never sees Wi-Fi, and the
 * files can be fetched over USB or with a file manager. It also proves the export format and the
 * whole upload bookkeeping without depending on anything Google does.
 */
final class FolderBackup implements Backup {

    static final String EXPORT = "export";

    private final File root;

    FolderBackup(File externalFilesDir) {
        this.root = new File(externalFilesDir, EXPORT);
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public String upload(String folder, String name, byte[] data) throws IOException {
        File dir = new File(root, folder);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        // Written beside the target and renamed, so a half-written file never
        // looks like a complete export. The temporary name carries the thread, so
        // two runs that overlap cannot write the same scratch file and then find
        // each other's rename has consumed it - which would leave a trip marked
        // as exported with nothing on disk.
        File target = new File(dir, name);
        File temp = new File(dir, name + "." + Thread.currentThread().getId() + ".part");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(data);
            out.getFD().sync();
        }
        // The rename replaces an existing export outright where the filesystem
        // allows it, which leaves no window in which neither file is there.
        if (!temp.renameTo(target)) {
            if (target.exists() && !target.delete()) {
                temp.delete();
                throw new IOException("cannot replace " + target);
            }
            if (!temp.renameTo(target)) {
                temp.delete();
                throw new IOException("cannot finish " + target);
            }
        }
        return target.getAbsolutePath();
    }

    /** Where exports land, for the settings screen to name. */
    File root() {
        return root;
    }
}

package pl.matsuo.navi;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

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
        // The second check is not redundant: two runs starting together both see
        // no folder, both call mkdirs, and the one that loses is told false
        // although the folder it wanted is now there. Without asking again, that
        // run fails for no reason and the backup is held back by its own backoff.
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
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
        move(temp, target);
        return target.getAbsolutePath();
    }

    /** How many times a replaced export is attempted before the run gives up. */
    private static final int MOVE_TRIES = 3;

    /**
     * Puts the finished file in place of the export, replacing whatever is there.
     *
     * <p>One move rather than a delete and then a rename: between those two a run that overlapped
     * this one could delete the file first, and then the delete here fails and this export throws
     * although nothing is wrong. A move leaves no window in which the export is missing, and two of
     * them racing leave the later one whole.
     *
     * <p>Retried, because replacing a file that another run is replacing at the same instant is
     * refused outright on some filesystems - Windows among them, which is where this is unit
     * tested. On the unit it is a Linux rename and never refused. A refusal costs one trip's export
     * until the next run, never the export already on disk.
     */
    private static void move(File temp, File target) throws IOException {
        IOException last = null;
        for (int i = 0; i < MOVE_TRIES; i++) {
            try {
                Files.move(
                        temp.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (AtomicMoveNotSupportedException e) {
                // Some filesystems cannot promise it; replacing is still better
                // than leaving the previous export half overwritten.
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        temp.delete();
        throw last;
    }

    /** Where exports land, for the settings screen to name. */
    File root() {
        return root;
    }
}

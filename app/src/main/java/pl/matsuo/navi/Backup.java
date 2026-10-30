package pl.matsuo.navi;

import java.io.IOException;

/**
 * Somewhere a finished trip can be copied to.
 *
 * <p>The launcher records to SQLite whatever happens here. A backend that is not configured, not
 * reachable, out of quota or refusing to authenticate must cost nothing but an unsynced trip, which
 * is why this interface is tiny and everything that uses it treats a failure as "try again later".
 *
 * <p>Google Drive is one implementation. {@link FolderBackup} is another that needs no account at
 * all, which is what makes the rest of the machinery testable and useful on a unit that never sees
 * the internet.
 */
interface Backup {

    /** True when this backend has everything it needs to try an upload. */
    boolean configured();

    /**
     * Copies one artifact.
     *
     * @param folder the year the trip belongs to
     * @param name the file name, from {@link Artifact#name}
     * @return an identifier for what was written, stored against the trip
     * @throws IOException for anything that might work next time
     */
    String upload(String folder, String name, byte[] data) throws IOException;
}

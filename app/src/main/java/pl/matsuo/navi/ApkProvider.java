package pl.matsuo.navi;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/**
 * Hands the downloaded APK to the package installer.
 *
 * <p>Since Android 7 an installer will not take a {@code file://} URI, and the usual answer is
 * AndroidX FileProvider. This app has no dependencies and is not about to gain one for sixty lines:
 * the installer needs exactly two things from a provider, a readable descriptor and the name and
 * size to show in its dialog, and that is all this does.
 *
 * <p>Not exported. The installer reads it through a one-shot read permission granted on the intent,
 * so no other app can ask for the file.
 */
public class ApkProvider extends ContentProvider {

    /** Must match the authority in the manifest. */
    static final String AUTHORITY = "pl.matsuo.navi.apk";

    static final String MIME = "application/vnd.android.package-archive";

    /** The folder downloads land in, inside the private app storage. */
    static final String DIR = "update";

    static File file(android.content.Context c, String name) {
        File dir = new File(c.getExternalFilesDir(null), DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, name);
    }

    static Uri uriFor(String name) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).path(name).build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    /** The downloaded file a URI refers to, kept inside the update folder. */
    private File resolve(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        // No path tricks: only a plain file name inside the update folder.
        if (name == null || name.contains("/") || name.contains("..")) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        File f = file(getContext(), name);
        if (!f.exists()) {
            throw new FileNotFoundException(f.getAbsolutePath());
        }
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(
            Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        try {
            File f = resolve(uri);
            String[] columns = {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor c = new MatrixCursor(columns, 1);
            c.addRow(new Object[] {f.getName(), f.length()});
            return c;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        return MIME;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }
}

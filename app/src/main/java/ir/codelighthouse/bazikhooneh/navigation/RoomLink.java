package ir.codelighthouse.bazikhooneh.navigation;

import android.net.Uri;
import java.util.Locale;
import java.util.regex.Pattern;

public final class RoomLink {
  private static final Pattern CODE = Pattern.compile("[A-Z0-9]{6}");
  private static final String WEB_HOST = "bazikhooneh.codelighthouse.ir";

  private RoomLink() {}

  public static String parseCode(Uri uri) {
    if (uri == null) return "";
    boolean custom =
        "bazikhooneh".equalsIgnoreCase(uri.getScheme()) && "room".equalsIgnoreCase(uri.getHost());
    boolean web =
        "https".equalsIgnoreCase(uri.getScheme())
            && WEB_HOST.equalsIgnoreCase(uri.getHost())
            && uri.getPathSegments().size() >= 2
            && "rooms".equalsIgnoreCase(uri.getPathSegments().get(0));
    if (!custom && !web) return "";
    String code = uri.getLastPathSegment();
    if (code == null) return "";
    code = code.trim().toUpperCase(Locale.ROOT);
    return CODE.matcher(code).matches() ? code : "";
  }

  public static String canonicalUrl(String code) {
    return "https://" + WEB_HOST + "/rooms/" + code.toUpperCase(Locale.ROOT);
  }
}

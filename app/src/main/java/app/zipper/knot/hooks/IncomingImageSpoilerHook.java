package app.zipper.knot.hooks;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;
import app.zipper.knot.Knot;
import app.zipper.knot.KnotConfig;
import app.zipper.knot.LineVersion;
import app.zipper.knot.LoadParam;
import app.zipper.knot.Main;
import app.zipper.knot.R;
import app.zipper.knot.Reflect;
import app.zipper.knot.utils.LineDBUtils;
import app.zipper.knot.utils.ModuleResources;
import io.github.libxposed.api.XposedInterface;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class IncomingImageSpoilerHook implements BaseHook {

  private static final int SPOILER_STATE_TAG = 0x647a0101;
  private static final int SPOILER_MESSAGE_ID_TAG = 0x647a0102;
  private static final int CACHE_LIMIT = 4096;
  private static final float MIN_IMAGE_DP = 72f;
  private static final float BLUR_RADIUS_DP = 24f;

  private static final Map<String, Boolean> incomingImageCache = new ConcurrentHashMap<>();
  private static final Set<String> revealedMessageIds = ConcurrentHashMap.newKeySet();
  private static volatile String cachedMyMid;

  private static final class CoveredImage {
    final ImageView image;
    final float originalAlpha;

    CoveredImage(ImageView image, float originalAlpha) {
      this.image = image;
      this.originalAlpha = originalAlpha;
    }
  }

  private static final class CoverView {
    final ViewGroup host;
    final View cover;

    CoverView(ViewGroup host, View cover) {
      this.host = host;
      this.cover = cover;
    }
  }

  private static final class SpoilerState {
    final String messageId;
    final List<CoveredImage> images = new ArrayList<>();
    final List<CoverView> covers = new ArrayList<>();

    SpoilerState(String messageId) {
      this.messageId = messageId;
    }
  }

  @Override
  public void hook(KnotConfig config, LoadParam lpparam) throws Throwable {
    LineVersion.Config cfg = LineVersion.get();
    if (cfg == null
        || isBlank(cfg.unsend.chatMessageViewHolderClass)
        || isBlank(cfg.unsend.methodBind)
        || isBlank(cfg.unsend.methodGetItemView)
        || isBlank(cfg.unsend.methodGetCommonData)) {
      Knot.log("Knot: Image spoiler unavailable: chat view metadata is incomplete");
      return;
    }

    Class<?> holderClass =
        Reflect.findClass(cfg.unsend.chatMessageViewHolderClass, lpparam.classLoader);
    Knot.hookAll(
        holderClass,
        cfg.unsend.methodBind,
        chain -> {
          Object result = chain.proceed();
          try {
            handleBinding(chain, cfg);
          } catch (Throwable t) {
            Knot.log("Knot: Image spoiler bind error: " + t);
          }
          return result;
        });

    Knot.log(
        "Knot: Incoming image spoiler hooked ("
            + cfg.unsend.chatMessageViewHolderClass
            + "."
            + cfg.unsend.methodBind
            + ")");
  }

  private static void handleBinding(XposedInterface.Chain chain, LineVersion.Config cfg)
      throws Exception {
    View root = (View) Reflect.callMethod(chain.getThisObject(), cfg.unsend.methodGetItemView);
    if (root == null) return;

    clearSpoiler(root);
    root.setTag(SPOILER_MESSAGE_ID_TAG, null);
    if (!isEnabled()) return;

    Object viewData = chain.getArg(cfg.unsend.methodBindIndex);
    if (viewData == null) return;

    Object commonData = Reflect.callMethod(viewData, cfg.unsend.methodGetCommonData);
    if (commonData == null) return;

    String messageId = getMessageId(commonData, cfg);
    if (isBlank(messageId)) return;

    root.setTag(SPOILER_MESSAGE_ID_TAG, messageId);
    installOneShotPreDraw(root, messageId);
  }

  private static void installOneShotPreDraw(View root, String messageId) {
    ViewTreeObserver observer = root.getViewTreeObserver();
    if (!observer.isAlive()) return;

    observer.addOnPreDrawListener(
        new ViewTreeObserver.OnPreDrawListener() {
          @Override
          public boolean onPreDraw() {
            ViewTreeObserver current = root.getViewTreeObserver();
            if (current.isAlive()) current.removeOnPreDrawListener(this);

            Object bound = root.getTag(SPOILER_MESSAGE_ID_TAG);
            if (!messageId.equals(bound) || revealedMessageIds.contains(messageId)) return true;

            List<ImageView> candidates = findMediaImageViews(root);
            if (candidates.isEmpty()) return true;

            if (!isIncomingImageMessage(messageId)) return true;

            conceal(root, messageId, candidates);
            return true;
          }
        });
  }

  private static String getMessageId(Object commonData, LineVersion.Config cfg) {
    try {
      Object value = Reflect.getObjectField(commonData, cfg.unsend.chatMessageIdField);
      if (value instanceof String && !((String) value).isEmpty()) return (String) value;
      if (value != null) return String.valueOf(value);
    } catch (Throwable ignored) {
    }

    try {
      if (!isBlank(cfg.unsend.chatMessageServerIdLongField)) {
        long value = Reflect.getLongField(commonData, cfg.unsend.chatMessageServerIdLongField);
        if (value > 0) return String.valueOf(value);
      }
    } catch (Throwable ignored) {
    }
    return null;
  }

  private static List<ImageView> findMediaImageViews(View root) {
    float density = root.getResources().getDisplayMetrics().density;
    int minPx = Math.max(1, Math.round(MIN_IMAGE_DP * density));

    List<ImageView> result = new ArrayList<>();
    collectImageViews(root, result, minPx);
    return result;
  }

  private static void collectImageViews(View view, List<ImageView> out, int minPx) {
    if (view instanceof ImageView) {
      ImageView image = (ImageView) view;
      if (image.getVisibility() == View.VISIBLE
          && image.getWidth() >= minPx
          && image.getHeight() >= minPx) {
        out.add(image);
      }
    }

    if (!(view instanceof ViewGroup)) return;
    ViewGroup group = (ViewGroup) view;
    for (int i = 0; i < group.getChildCount(); i++) {
      collectImageViews(group.getChildAt(i), out, minPx);
    }
  }

  private static boolean isIncomingImageMessage(String messageId) {
    Boolean cached = incomingImageCache.get(messageId);
    if (cached != null) return cached;

    boolean result = queryIncomingImage(messageId);
    if (incomingImageCache.size() >= CACHE_LIMIT) incomingImageCache.clear();
    incomingImageCache.put(messageId, result);
    return result;
  }

  private static boolean queryIncomingImage(String messageId) {
    Context context = Knot.currentApplication();
    if (context == null) return false;

    File dbFile = context.getDatabasePath("naver_line");
    if (!dbFile.exists()) return false;

    try (SQLiteDatabase db =
        SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY)) {
      Boolean byServerId =
          queryIncomingImageRow(
              db,
              "SELECT attachement_type, parameter, from_mid FROM chat_history WHERE server_id = ? LIMIT 1",
              new String[] {messageId});
      if (byServerId != null) return byServerId;

      if (isDecimal(messageId)) {
        Boolean byLocalId =
            queryIncomingImageRow(
                db,
                "SELECT attachement_type, parameter, from_mid FROM chat_history WHERE id = ? LIMIT 1",
                new String[] {messageId});
        if (byLocalId != null) return byLocalId;
      }
    } catch (Throwable t) {
      Knot.log("Knot: Image spoiler database lookup failed: " + t);
    }
    return false;
  }

  private static Boolean queryIncomingImageRow(SQLiteDatabase db, String sql, String[] args) {
    try (Cursor cursor = db.rawQuery(sql, args)) {
      if (!cursor.moveToFirst()) return null;

      int attachmentType = cursor.isNull(0) ? -1 : cursor.getInt(0);
      String parameter = cursor.getString(1);
      String fromMid = cursor.getString(2);
      if (isOwnMessage(fromMid)) return false;

      if (attachmentType == 1) return true;
      if (attachmentType > 1) return false;
      if (parameter == null || parameter.isEmpty()) return false;

      String normalized = parameter.toUpperCase(Locale.ROOT);
      return normalized.contains("IMAGE") && !normalized.contains("VIDEO");
    }
  }

  private static void conceal(View root, String messageId, List<ImageView> candidates) {
    clearSpoiler(root);
    if (revealedMessageIds.contains(messageId)) return;
    if (!messageId.equals(root.getTag(SPOILER_MESSAGE_ID_TAG))) return;

    SpoilerState state = new SpoilerState(messageId);
    float density = root.getResources().getDisplayMetrics().density;

    for (ImageView image : candidates) {
      if (image.getWidth() <= 0 || image.getHeight() <= 0) continue;
      if (!(image.getParent() instanceof ViewGroup)) continue;

      ViewGroup host = (ViewGroup) image.getParent();
      float originalAlpha = image.getAlpha();
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Api31.applyBlur(image, BLUR_RADIUS_DP * density);
      } else {
        image.setAlpha(Math.min(originalAlpha, 0.06f));
      }

      TextView cover = new TextView(image.getContext());
      cover.setText(ModuleResources.get(R.string.image_spoiler_tap_to_reveal));
      cover.setTextColor(Color.WHITE);
      cover.setTextSize(14f);
      cover.setGravity(Gravity.CENTER);
      cover.setBackgroundColor(
          Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? 0x66202225 : 0xf0202225);
      cover.setClickable(true);
      cover.setFocusable(true);
      int padding = Math.round(12f * density);
      cover.setPadding(padding, padding, padding, padding);
      cover.setOnClickListener(v -> reveal(root, messageId));

      host.getOverlay().add(cover);
      cover.layout(0, 0, image.getWidth(), image.getHeight());
      cover.setTranslationX(image.getLeft() + image.getTranslationX());
      cover.setTranslationY(image.getTop() + image.getTranslationY());

      state.images.add(new CoveredImage(image, originalAlpha));
      state.covers.add(new CoverView(host, cover));
    }

    if (!state.images.isEmpty()) root.setTag(SPOILER_STATE_TAG, state);
  }

  private static void reveal(View root, String messageId) {
    Object bound = root.getTag(SPOILER_MESSAGE_ID_TAG);
    if (!messageId.equals(bound)) return;

    revealedMessageIds.add(messageId);
    clearSpoiler(root);
  }

  private static void clearSpoiler(View root) {
    Object value = root.getTag(SPOILER_STATE_TAG);
    if (value instanceof SpoilerState) {
      SpoilerState state = (SpoilerState) value;

      for (CoverView cover : state.covers) {
        try {
          cover.host.getOverlay().remove(cover.cover);
        } catch (Throwable ignored) {
        }
      }

      for (CoveredImage covered : state.images) {
        try {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Api31.clearBlur(covered.image);
          } else {
            covered.image.setAlpha(covered.originalAlpha);
          }
        } catch (Throwable ignored) {
        }
      }
    }

    root.setTag(SPOILER_STATE_TAG, null);
  }

  private static boolean isEnabled() {
    return Main.options.hideIncomingImages.enabled;
  }

  private static boolean isOwnMessage(String fromMid) {
    if (fromMid == null || fromMid.isEmpty()) return true;

    String myMid = cachedMyMid;
    if (myMid == null || myMid.isEmpty()) {
      myMid = LineDBUtils.getMyMid();
      if (myMid != null && !myMid.isEmpty()) cachedMyMid = myMid;
    }
    return myMid != null && myMid.equals(fromMid);
  }

  private static boolean isDecimal(String value) {
    if (value == null || value.isEmpty()) return false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < '0' || c > '9') return false;
    }
    return true;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isEmpty();
  }

  private static final class Api31 {
    private Api31() {}

    static void applyBlur(View view, float radiusPx) {
      view.setRenderEffect(
          RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP));
    }

    static void clearBlur(View view) {
      view.setRenderEffect(null);
    }
  }
}

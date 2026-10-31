package app.zipper.knot.hooks;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Conceals incoming photo thumbnails until explicitly revealed. A clickable FrameLayout child is
 * used instead of ViewGroupOverlay (overlay drawables/views do not receive touch events).
 */
public class IncomingImageSpoilerHook implements BaseHook {
  private static final int STATE_TAG = 0x647a0101;
  private static final int MESSAGE_TAG = 0x647a0102;
  private static final float MIN_IMAGE_DP = 72f;
  private static final int MAX_CACHE = 2048;
  private static final ThreadPoolExecutor LOOKUP =
      new ThreadPoolExecutor(
          1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(96));

  private static final Map<String, Boolean> imageCache =
      Collections.synchronizedMap(
          new LinkedHashMap<String, Boolean>(MAX_CACHE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
              return size() > MAX_CACHE;
            }
          });
  private static final Set<String> revealed =
      Collections.synchronizedSet(
          Collections.newSetFromMap(
              new LinkedHashMap<String, Boolean>(MAX_CACHE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                  return size() > MAX_CACHE;
                }
              }));
  private static volatile String myMid;

  private static final class Cover {
    final FrameLayout host;
    final TextView label;

    Cover(FrameLayout host, TextView label) {
      this.host = host;
      this.label = label;
    }
  }

  private static final class State {
    final String id;
    final List<Cover> covers = new ArrayList<>();

    State(String id) {
      this.id = id;
    }
  }

  @Override
  public void hook(KnotConfig config, LoadParam lpparam) throws Throwable {
    LineVersion.Config cfg = LineVersion.get();
    if (cfg == null
        || blank(cfg.unsend.chatMessageViewHolderClass)
        || blank(cfg.unsend.methodBind)
        || blank(cfg.unsend.methodGetItemView)
        || blank(cfg.unsend.methodGetCommonData)
        || (blank(cfg.unsend.chatMessageIdField)
            && blank(cfg.unsend.chatMessageServerIdLongField))) {
      Knot.log("Knot: Image spoiler skipped: message holder mapping is unavailable");
      return;
    }

    Class<?> holder = Reflect.findClass(cfg.unsend.chatMessageViewHolderClass, lpparam.classLoader);
    Knot.hookAll(
        holder,
        cfg.unsend.methodBind,
        chain -> {
          Object result = chain.proceed();
          try {
            onBind(chain, cfg);
          } catch (Throwable e) {
            Knot.log("Knot: Image spoiler bind failed: " + e);
          }
          return result;
        });
    Knot.log("Knot: Incoming image spoiler enabled");
  }

  private static void onBind(XposedInterface.Chain chain, LineVersion.Config cfg) {
    Object item = Reflect.callMethod(chain.getThisObject(), cfg.unsend.methodGetItemView);
    if (!(item instanceof View)) return;
    View root = (View) item;
    clear(root);
    root.setTag(MESSAGE_TAG, null);
    if (!Main.options.hideIncomingImages.enabled) return;

    Object model = chain.getArg(cfg.unsend.methodBindIndex);
    if (model == null) return;
    Object common = Reflect.callMethod(model, cfg.unsend.methodGetCommonData);
    if (common == null) return;
    String id = messageId(common, cfg);
    if (blank(id)) return;
    root.setTag(MESSAGE_TAG, id);
    if (revealed.contains(id)) return;

    ViewTreeObserver observer = root.getViewTreeObserver();
    if (!observer.isAlive()) return;
    observer.addOnPreDrawListener(
        new ViewTreeObserver.OnPreDrawListener() {
          @Override
          public boolean onPreDraw() {
            ViewTreeObserver current = root.getViewTreeObserver();
            if (current.isAlive()) current.removeOnPreDrawListener(this);
            if (!id.equals(root.getTag(MESSAGE_TAG)) || revealed.contains(id)) return true;

            // Install opaque masks *before* the first frame. Async DB checks must not briefly
            // expose a spoiler; only sufficiently large ImageViews are provisional candidates.
            State state = coverCandidates(root, id);
            if (state.covers.isEmpty()) return true;
            root.setTag(STATE_TAG, state);

            Boolean cached = imageCache.get(id);
            if (cached != null) {
              resolve(root, state, cached);
            } else {
              try {
                LOOKUP.execute(
                    () -> {
                      boolean incoming;
                      try {
                        incoming = isIncomingPhoto(id);
                      } catch (Throwable e) {
                        Knot.log("Knot: Image spoiler lookup error: " + e);
                        incoming = false;
                      }
                      imageCache.put(id, incoming);
                      final boolean result = incoming;
                      root.post(() -> resolve(root, state, result));
                    });
              } catch (RuntimeException e) {
                Knot.log("Knot: Image spoiler lookup rejected: " + e);
                clear(root);
              }
            }
            return true;
          }
        });
  }

  private static State coverCandidates(View root, String id) {
    State state = new State(id);
    int threshold = Math.round(MIN_IMAGE_DP * root.getResources().getDisplayMetrics().density);
    List<ImageView> images = new ArrayList<>();
    findImages(root, threshold, images);
    for (ImageView image : images) {
      FrameLayout host = findFrameParent(image, root);
      if (host == null) continue; // Do not alter LINE's click listeners or layout as a fallback.
      Rect bounds = new Rect(0, 0, image.getWidth(), image.getHeight());
      try {
        host.offsetDescendantRectToMyCoords(image, bounds);
        if (bounds.width() <= 0 || bounds.height() <= 0) continue;
        TextView cover = new TextView(image.getContext());
        cover.setText(ModuleResources.get(R.string.image_spoiler_loading));
        cover.setTextColor(Color.WHITE);
        cover.setTextSize(14);
        cover.setGravity(Gravity.CENTER);
        cover.setBackgroundColor(0xFF303239);
        cover.setClickable(true);
        cover.setFocusable(true);
        cover.setContentDescription(ModuleResources.get(R.string.image_spoiler_tap_to_reveal));
        FrameLayout.LayoutParams lp =
            new FrameLayout.LayoutParams(bounds.width(), bounds.height(), Gravity.TOP | Gravity.LEFT);
        lp.leftMargin = bounds.left;
        lp.topMargin = bounds.top;
        host.addView(cover, lp);
        state.covers.add(new Cover(host, cover));
      } catch (Throwable e) {
        Knot.log("Knot: Image spoiler overlay skipped: " + e);
      }
    }
    return state;
  }

  private static FrameLayout findFrameParent(View view, View root) {
    ViewParent parent = view.getParent();
    while (parent instanceof View) {
      if (parent instanceof FrameLayout) return (FrameLayout) parent;
      if (parent == root) break;
      parent = parent.getParent();
    }
    return null;
  }

  private static void findImages(View root, int threshold, List<ImageView> images) {
    if (root instanceof ImageView
        && root.getVisibility() == View.VISIBLE
        && root.getWidth() >= threshold
        && root.getHeight() >= threshold) images.add((ImageView) root);
    if (!(root instanceof ViewGroup)) return;
    ViewGroup group = (ViewGroup) root;
    for (int i = 0; i < group.getChildCount(); i++) {
      findImages(group.getChildAt(i), threshold, images);
    }
  }

  private static void resolve(View root, State state, boolean incoming) {
    if (root.getTag(STATE_TAG) != state || !state.id.equals(root.getTag(MESSAGE_TAG))) return;
    if (!incoming || revealed.contains(state.id)) {
      clear(root);
      return;
    }
    for (Cover entry : state.covers) {
      entry.label.setText(ModuleResources.get(R.string.image_spoiler_tap_to_reveal));
      entry.label.setOnClickListener(v -> {
        if (!state.id.equals(root.getTag(MESSAGE_TAG))) return;
        revealed.add(state.id);
        clear(root);
      });
    }
  }

  private static void clear(View root) {
    Object obj = root.getTag(STATE_TAG);
    if (obj instanceof State) {
      for (Cover entry : ((State) obj).covers) {
        try {
          entry.host.removeView(entry.label);
        } catch (Throwable ignored) {
        }
      }
    }
    root.setTag(STATE_TAG, null);
  }

  private static String messageId(Object common, LineVersion.Config cfg) {
    try {
      Object id = Reflect.getObjectField(common, cfg.unsend.chatMessageIdField);
      if (id != null && !id.toString().isEmpty()) return id.toString();
    } catch (Throwable ignored) {
    }
    try {
      if (!blank(cfg.unsend.chatMessageServerIdLongField)) {
        long id = Reflect.getLongField(common, cfg.unsend.chatMessageServerIdLongField);
        if (id > 0) return String.valueOf(id);
      }
    } catch (Throwable ignored) {
    }
    return null;
  }

  private static boolean isIncomingPhoto(String id) {
    Context context = Knot.currentApplication();
    if (context == null) return false;
    File file = context.getDatabasePath("naver_line");
    if (!file.exists()) return false;
    try (SQLiteDatabase db =
        SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY)) {
      Boolean byServer =
          lookup(db, "SELECT attachement_type, parameter, from_mid FROM chat_history WHERE server_id = ? LIMIT 1", id);
      if (byServer != null) return byServer;
      if (decimal(id)) {
        Boolean byRow =
            lookup(db, "SELECT attachement_type, parameter, from_mid FROM chat_history WHERE id = ? LIMIT 1", id);
        if (byRow != null) return byRow;
      }
    } catch (Throwable e) {
      Knot.log("Knot: Image spoiler database lookup failed: " + e);
    }
    return false;
  }

  private static Boolean lookup(SQLiteDatabase db, String sql, String id) {
    try (Cursor cursor = db.rawQuery(sql, new String[] {id})) {
      if (!cursor.moveToFirst()) return null;
      String sender = cursor.getString(2);
      if (blank(sender)) return false;
      String own = myMid;
      if (blank(own)) {
        own = LineDBUtils.getMyMid();
        if (!blank(own)) myMid = own;
      }
      if (blank(own) || own.equals(sender)) return false; // Fail open if sender is unknown.
      int type = cursor.isNull(0) ? -1 : cursor.getInt(0);
      if (type == 1) return true;
      if (type > 1) return false;
      String parameter = cursor.getString(1);
      if (blank(parameter)) return false;
      String value = parameter.toUpperCase(Locale.ROOT);
      return value.contains("IMAGE") && !value.contains("VIDEO");
    }
  }

  private static boolean decimal(String id) {
    for (int i = 0; i < id.length(); i++)
      if (id.charAt(i) < '0' || id.charAt(i) > '9') return false;
    return !id.isEmpty();
  }

  private static boolean blank(String s) {
    return s == null || s.isEmpty();
  }
}

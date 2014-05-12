package com.mohammadag.googlesearchapi;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.getObjectField;

import java.lang.reflect.Method;
import java.util.ArrayList;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.text.TextUtils;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class GoogleSearchAPIModule implements IXposedHookLoadPackage, IXposedHookZygoteInit {

	private Context mContext = null;
	private static ArrayList<Intent> mQueuedIntentList = null;
	private static XSharedPreferences mPreferences;

	@Override
	public void initZygote(StartupParam startupParam) throws Throwable {
		mPreferences = new XSharedPreferences("com.mohammadag.googlesearchapi");
	}

	@Override
	public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
		if (lpparam.packageName.equals("com.mohammadag.googlesearchapi")) {
			touchOurself(lpparam.classLoader);
		}

		if (!lpparam.packageName.equals(Constants.GOOGLE_SEARCH_PACKAGE))
			return;
		debug("Processing " + Constants.GOOGLE_SEARCH_PACKAGE);
		hookV34(lpparam);

	}
	
	private void hookV34(LoadPackageParam lpparam) {
		/* IPC, not sure how many processes Google Search runs in, but we need this since
		 * it's surely not one.
		 */
		debug("create BroadcastReceiver");
		final BroadcastReceiver internalReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				if (Constants.INTENT_SETTINGS_UPDATED.equals(intent.getAction())) {
					mPreferences.reload();
				} else if (Constants.INTENT_QUEUE_INTENT.equals(intent.getAction())) {
					Intent intentToQueue = intent.getParcelableExtra(Constants.KEY_INTENT_TO_QUEUE);
					mQueuedIntentList.add(intentToQueue);
				} else if (Constants.INTENT_FLUSH_INTENTS.equals(intent.getAction())) {
					for (Intent intentToFlush : mQueuedIntentList) {
						XposedBridge.log("Sending queued intent");
						sendBroadcast(mContext, intentToFlush);
						mQueuedIntentList.remove(intentToFlush);
					}
				}
			}
		};

		debug("findClass azs (com.google.android.search.core.SearchController)");
		// obfuscated name of com.google.android.search.core.SearchController is azs
		Class<?> SearchController = findClass("azs", lpparam.classLoader);

		debug("hookAllConstructors azs (com.google.android.search.core.SearchController)");
		XposedBridge.hookAllConstructors(SearchController, new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				final Object thisObject = param.thisObject;
				debug("getObjectField mContext on " + param.thisObject);
				mContext = (Context) getObjectField(param.thisObject, "mContext");
				mQueuedIntentList = new ArrayList<Intent>();
				mContext.registerReceiver(new BroadcastReceiver() {
					@Override
					public void onReceive(Context context, Intent intent) {
						String string = intent.getStringExtra(GoogleSearchApi.KEY_TEXT_TO_SPEAK);
						if (TextUtils.isEmpty(string))
							return;
						debug("received string " + string);
						
						// mVoiceSearchServices has the same name in "azs" but has type "fio"
						debug("getObjectField mVoiceSearchServices on " + thisObject);
						Object mVoiceSearchServices = getObjectField(thisObject, "mVoiceSearchServices");
						// getLocalTtsManager obfuscated name is "asi"
						debug("callMethod asi() on " + mVoiceSearchServices);
						Object ttsManager = XposedHelpers.callMethod(mVoiceSearchServices, "asi");
						// LocalTtsManager class is now called "geg", "enqueue" is now "a"
						debug("findMethodBestMatch a(String, Runnable) on " + ttsManager.getClass());
						Method method = XposedHelpers.findMethodBestMatch(ttsManager.getClass(), "a", String.class, Runnable.class);
						try {
							method.invoke(ttsManager, string, null);
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				}, new IntentFilter(GoogleSearchApi.INTENT_REQUEST_SPEAK));

				IntentFilter iF = new IntentFilter();
				iF.addAction(Constants.INTENT_SETTINGS_UPDATED);
				iF.addAction(Constants.INTENT_FLUSH_INTENTS);
				iF.addAction(Constants.INTENT_QUEUE_INTENT);

				mContext.registerReceiver(internalReceiver, iF);
			}
		});


		debug("findClass com.google.android.shared.search.Query (com.google.android.search.shared.api.Query)");
		// com.google.android.search.shared.api.Query is now com.google.android.shared.search.Query
		Class<?> Query = findClass("com.google.android.shared.search.Query", lpparam.classLoader);

		debug("findClass blq (com.google.android.search.core.prefetch.SearchResultFetcher)");
		// com.google.android.search.core.prefetch.SearchResultFetcher is now "blq"
		Class<?> SearchResultFetcher = findClass("blq", lpparam.classLoader);
		// obtainSearchResult is now "s"
		debug("findAndHookMethod s(Query) on " + SearchResultFetcher);
		findAndHookMethod(SearchResultFetcher, "s", Query, new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
				Object queryResult = param.args[0];
				// mQueryChars is now "bjt"
				debug("getObjectField bjt on " + queryResult);
				CharSequence searchQueryText =
						(CharSequence) getObjectField(queryResult, "bjt");
				debug("getObjectField mCache on " + param.thisObject);
				Object mCache = getObjectField(param.thisObject, "mCache");
				debug("getObjectField mClock on " + param.thisObject);
				Object mClock = getObjectField(param.thisObject, "mClock");
				// get is now "a"
				debug("callMethod a(?, elapsedRealtime, true) on " + mCache);
				Object mCachedResult = XposedHelpers.callMethod(mCache, "a", queryResult,
						XposedHelpers.callMethod(mClock, "elapsedRealtime"),
						true);
				
				mPreferences.reload();

				/* Not doing this causes a usability issue. If the user has a search showing
				 * results, and they tap the mic, then cancel the voice search, then the search
				 * is handled again, thus throwing the user in an infinite loop of pressing back,
				 * until the user figures it out and uses the task switcher to close Google Search.
				 */
				if (mCachedResult != null && mContext != null
						&& mPreferences.getBoolean(Constants.KEY_PREVENT_DUPLICATES, true)) {
					return;
				}

				if (mContext != null) {
					broadcastGoogleSearch(mContext, searchQueryText, false,
							mPreferences.getBoolean(Constants.KEY_DELAY_BROADCASTS, false));
				} else {
					debug(String.format("Google Search API: New Search detected: %s",
							searchQueryText.toString()));
				}
			}
		});

		debug("findClass bae (com.google.android.search.core.SearchController$MyVoiceSearchControllerListener)");
		// com.google.android.search.core.SearchController$MyVoiceSearchControllerListener is now "bae"
		Class<?> MyVoiceSearchControllerListener =
				findClass("bae", lpparam.classLoader);
		// onRecognitionResult is now "public final void a(CharSequence paramCharSequence, glq paramglq, blk paramblk)"
		
		debug("findClass glq");
		Class<?> glq = findClass("glq", lpparam.classLoader);
		debug("findClass blk");
		Class<?> blk = findClass("blk", lpparam.classLoader);
		
		debug("findMethodBestMatch a(CharSequence, " + glq + ", " + blk + ") on " + MyVoiceSearchControllerListener);
		Method method = XposedHelpers.findMethodBestMatch(MyVoiceSearchControllerListener, "a", CharSequence.class, glq, blk);
		debug("hookMethod " + method);
		XposedBridge.hookMethod(method, new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
				CharSequence voiceResult = (CharSequence) param.args[0];
				mPreferences.reload();
				if (mContext != null) {
					debug("broadcastGoogleSearch " + voiceResult.toString());
					broadcastGoogleSearch(mContext, voiceResult, true,
							mPreferences.getBoolean(Constants.KEY_DELAY_BROADCASTS, false));
				} else {
					debug(voiceResult.toString());
				}
			}
		});
		
		/* GEL workaround, GEL opens Google Search eventually, so this will overlay whatever
		 * activity a developer has made. This broadcasts intents after the window has gained focus.
		 */
		debug("findAndHookMethod ccu onWindowFocusChanged(boolean) (com.google.android.search.gel.SearchOverlayImpl)");
		// com.google.android.search.gel.SearchOverlayImpl is now "ccu"
		findAndHookMethod("ccu", lpparam.classLoader,
				"onWindowFocusChanged", boolean.class, new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				boolean hasFocus = (Boolean) param.args[0];
				debug("getObjectField mContext on " + param.thisObject);
				Context context = (Context) getObjectField(param.thisObject, "mContext");
				if (context != null && !hasFocus)
					context.sendBroadcast(new Intent(Constants.INTENT_FLUSH_INTENTS));
			}
		});		
	}

	private void debug(String msg) {
		Log.d("GoogleSearchAPI", msg);
		XposedBridge.log(msg);
	}

	private void touchOurself(ClassLoader classLoader) {
		findAndHookMethod("com.mohammadag.googlesearchapi.UiUtils", classLoader,
				"isHookActive", XC_MethodReplacement.returnConstant(true));
	}

	private static void broadcastGoogleSearch(Context context, CharSequence searchText, boolean voice, boolean delayed) {
		Intent intent = new Intent(GoogleSearchApi.INTENT_NEW_SEARCH);
		intent.putExtra(GoogleSearchApi.KEY_VOICE_TYPE, voice);
		intent.putExtra(GoogleSearchApi.KEY_QUERY_TEXT, searchText.toString());
		if (delayed) {
			mQueuedIntentList.add(intent);
		} else {
			sendBroadcast(context, intent);
		}
	}

	private static void sendBroadcast(Context context, Intent intent) {
		context.sendBroadcast(intent, Constants.PERMISSION);
	}
}

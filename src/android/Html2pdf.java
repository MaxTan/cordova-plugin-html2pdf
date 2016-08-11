package th.azay.cordova.plugin.html2pdf;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.pdf.PdfWriter;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

@TargetApi(19)
public class Html2pdf extends CordovaPlugin {
    private static final String LOG_TAG = "Html2Pdf";
    private CallbackContext callbackContext;

    // change your path on the sdcard here
    private String publicTmpDir = ".th.azay.cordova.plugin.html2pdf"; // prepending a dot "." would make it hidden
    private String tmpPdfName = "print.pdf";

    // set to true to see the webview (useful for debugging)
    private final boolean showWebViewForDebugging = false;

    /**
     * Constructor.
     */
    public Html2pdf() {

    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        try {
            if (action.equals("create")) {
                if (showWebViewForDebugging) {
                    Log.v(LOG_TAG, "java create pdf from html called");
                    Log.v(LOG_TAG, "File: " + args.getString(1));
                    // Log.v(LOG_TAG, "Html: " + args.getString(0));
                    Log.v(LOG_TAG, "Html start:" + args.getString(0).substring(0, 30));
                    Log.v(LOG_TAG, "Html end:" + args.getString(0).substring(args.getString(0).length() - 30));
                }

                if (args.getString(1) != null && args.getString(1) != "null")
                    this.tmpPdfName = args.getString(1).replace("file://", "");

                final Html2pdf self = this;
                final String content = args.optString(0, "<html></html>");
                this.callbackContext = callbackContext;

                cordova.getActivity().runOnUiThread(new Runnable() {
                    public void run() {
                        self.loadContentIntoWebView(content);
                    }
                });

                // send "no-result" result to delay result handling
                PluginResult pluginResult = new PluginResult(PluginResult.Status.NO_RESULT);
                pluginResult.setKeepCallback(true);
                callbackContext.sendPluginResult(pluginResult);

                return true;
            }
            return false;
        } catch (JSONException e) {
            // TODO: signal JSON problem to JS
            //callbackContext.error("Problem with JSON");
            callbackContext.error(e.getMessage());
            return false;
        }
    }


    /**
     * Clean up and close all open files.
     */
    @Override
    public void onDestroy() {
        // ToDo: clean up.
    }

    // --------------------------------------------------------------------------
    // LOCAL METHODS
    // --------------------------------------------------------------------------


    /**
     * Loads the html content into a WebView, saves it as a single multi page pdf file and
     * calls startPdfApp() once it´s done.
     */
    private void loadContentIntoWebView(String content) {
        Activity ctx = cordova.getActivity();
        final WebView page = new Html2PdfWebView(ctx);
        final Html2pdf self = this;

        page.setVerticalScrollBarEnabled(false);
        page.setHorizontalScrollBarEnabled(false);

        if (showWebViewForDebugging) {
            page.setVisibility(View.VISIBLE);
        } else {
            page.setVisibility(View.INVISIBLE);
        }

        page.getSettings().setJavaScriptEnabled(true);
        page.setDrawingCacheEnabled(true);
        // Don´t auto-scale the content to the webview's width.
        page.getSettings().setLoadWithOverviewMode(true);
        page.getSettings().setUseWideViewPort(true);
        page.setInitialScale(300);
        // Disable android text auto fit behaviour
        page.getSettings().setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);

        page.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(final WebView page, String url) {
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        // slice the web screenshot into pages and save as pdf
                        Bitmap b = getWebViewAsBitmap(page);

                        PluginResult result;

                        if (b != null) {
                            self.saveWebViewAsPdf(b);

                            b.recycle();

                            // remove the webview
                            if (!self.showWebViewForDebugging) {
                                ViewGroup vg = (ViewGroup) (page.getParent());
                                vg.removeView(page);
                            }
                            // send success result to cordova
                            result = new PluginResult(PluginResult.Status.OK);
                            result.setKeepCallback(false);
                            self.callbackContext.sendPluginResult(result);
                        } else {
                            // send error
                            result = new PluginResult(PluginResult.Status.ERROR, "source_not_found");
                            result.setKeepCallback(false);
                            self.callbackContext.sendPluginResult(result);
                        }
                    }
                }, 500);
            }
        });

        // Set base URI to the assets/www folder
        String baseURL = webView.getUrl();
        baseURL = baseURL.substring(0, baseURL.lastIndexOf('/') + 1);

        /** We make it this small on purpose (is resized after page load has finished).
         *  Making it small in the beginning has some effects on the html <body> (body
         *  width will always remain 100 if not set explicitly).
         */
        if (!showWebViewForDebugging) {
            ctx.addContentView(page, new ViewGroup.LayoutParams(100, 100));
        } else {
            ctx.addContentView(page, new ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        page.loadDataWithBaseURL(baseURL, content, "text/html", "utf-8", null);
    }

    public static final String MIME_TYPE_PDF = "application/pdf";

    /**
     * Check if the supplied context can handle the given intent.
     *
     * @param context
     * @param intent
     * @return boolean
     */
    public boolean canHandleIntent(Context context, Intent intent) {
        PackageManager packageManager = context.getPackageManager();
        return (packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).size() > 0);
    }

    /**
     * Takes a WebView and returns a Bitmap representation of it (takes a "screenshot").
     *
     * @param WebView
     * @return Bitmap
     */
    Bitmap getWebViewAsBitmap(WebView view) {
        Bitmap b;

        // prepare drawing cache
        view.setDrawingCacheEnabled(true);
        view.buildDrawingCache();

        //Get the dimensions of the view so we can re-layout the view at its current size
        //and create a bitmap of the same size
        int width = ((Html2PdfWebView) view).getContentWidth();
        int height = view.getContentHeight();

        if (width == 0 || height == 0) {
            // return error answer to cordova
            String msg = "Width or height of webview content is 0. Webview to bitmap conversion failed.";
            Log.e(LOG_TAG, msg);
            PluginResult result = new PluginResult(PluginResult.Status.ERROR, msg);
            result.setKeepCallback(false);
            callbackContext.sendPluginResult(result);

            return null;
        }

        Log.v(LOG_TAG, "Html2Pdf.getWebViewAsBitmap -> Content width: " + width + ", height: " + height);

        //Cause the view to re-layout
        view.measure(width, height);
        view.layout(0, 0, width, height);

        //Create a bitmap backed Canvas to draw the view into
        b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);

        // draw the view into the canvas
        view.draw(c);

        return b;
    }

    /**
     * Slices the screenshot into pages, merges those into a single pdf
     * and saves it in the public accessible /sdcard dir.
     */
    private File saveWebViewAsPdf(Bitmap screenshot) {
        try {

            File sdCard = Environment.getExternalStorageDirectory();
            //Log.v(LOG_TAG,sdCard);
            //File dir = new File (sdCard.getAbsolutePath() + "/" + this.publicTmpDir + "/");
            File dir = new File(sdCard.getAbsolutePath() + "/" + this.publicTmpDir + "/");
            //Log.i(LOG_TAG,dir);
            dir.mkdirs();
            File file;
            FileOutputStream stream;

            // creat nomedia file to avoid indexing tmp files
            File noMediaFile = new File(dir.getAbsolutePath() + "/", ".nomedia");
            if (!noMediaFile.exists()) {
                noMediaFile.createNewFile();
            }

            double pageWidth = PageSize.A4.getWidth();//  * 0.85; // width of the image is 85% of the page
            double pageHeight = PageSize.A4.getHeight();// * 0.79; // max height of the image is 80% of the page
            double pageHeightToWithRelation = pageHeight / pageWidth; // e.g.: 1.33 (4/3)

            Bitmap currPage;
            int totalSize = screenshot.getHeight();
            int currPos = 0;
            int currPageCount = 0;
            int sliceWidth = screenshot.getWidth();
            int sliceHeight = (int) Math.round(sliceWidth * pageHeightToWithRelation);
            while (totalSize > currPos && currPageCount < 100) // max 100 pages
            {
                currPageCount++;

                Log.v(LOG_TAG, "Creating page nr. " + currPageCount);

                // slice bitmap
                currPage = Bitmap.createBitmap(screenshot, 0, currPos, sliceWidth, (int) Math.min(sliceHeight, totalSize - currPos));

                // save page as png
                stream = new FileOutputStream(new File(dir, "pdf-page-" + currPageCount + ".png"));
                currPage.compress(Bitmap.CompressFormat.PNG, 100, stream);
                stream.close();

                // move current position indicator
                currPos += sliceHeight;

                currPage.recycle();
            }

            // create pdf
            Document document = new Document();
            File filePdf = new File(this.tmpPdfName); // change the output name of the pdf here
            PdfWriter.getInstance(document, new FileOutputStream(filePdf));
            document.open();
            for (int i = 1; i <= currPageCount; ++i) {
                file = new File(dir, "pdf-page-" + i + ".png");
                Image image = Image.getInstance(file.getAbsolutePath());
                image.scaleToFit((float) pageWidth, 9999);
                image.setAlignment(Element.ALIGN_CENTER);
                document.add(image);
                document.newPage();
            }
            document.close();

            // delete tmp image files
            for (int i = 1; i <= currPageCount; ++i) {
                file = new File(dir, "pdf-page-" + i + ".png");
                file.delete();
            }

            return filePdf;

        } catch (IOException e) {
            Log.e(LOG_TAG, "ERROR: " + e.getMessage());
            e.printStackTrace();
            // return error answer to cordova
            PluginResult result = new PluginResult(PluginResult.Status.ERROR, e.getMessage());
            result.setKeepCallback(false);
            callbackContext.sendPluginResult(result);
        } catch (DocumentException e) {
            Log.e(LOG_TAG, "ERROR: " + e.getMessage());
            e.printStackTrace();
            // return error answer to cordova
            PluginResult result = new PluginResult(PluginResult.Status.ERROR, e.getMessage());
            result.setKeepCallback(false);
            callbackContext.sendPluginResult(result);
        }

        Log.v(LOG_TAG, "Uncaught ERROR!");

        return null;
    }


}

class Html2PdfWebView extends WebView {
    public Html2PdfWebView(Context context) {
        super(context);
    }

    public int getContentWidth() {
        return this.computeHorizontalScrollRange();
    }
}

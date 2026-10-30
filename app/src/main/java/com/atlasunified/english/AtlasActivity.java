package com.atlasunified.english;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.util.Log;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.HttpURLConnection;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public class AtlasActivity extends Activity {
    private static final String HOST="appassets.androidplatform.net";
    private static final String HOME="https://"+HOST+"/assets/index.html";
    private static final int PICK_VIDEO=9011;
    private static final Set<String> HOSTS=new HashSet<>(Arrays.asList("ai-costume-gallery.pages.dev","ai-pose-gallery.pages.dev","ai-pose-gallery.anhao.app"));
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private WebView web;
    private ValueCallback<Uri[]> videoSelection;
    private int rendererRecovery=0;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(17,18,29));
        getWindow().setNavigationBarColor(Color.rgb(17,18,29));
        setContentView(makeRootView());
        web.loadUrl(HOME);
    }
    // Android 15+ enforces edge-to-edge. The WebView must be inset below the
    // system status bar or gallery tabs are clipped and receive no taps.
    private FrameLayout makeRootView(){
        final FrameLayout root=new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(17,18,29));
        root.setClipToPadding(true);
        root.addView(makeWebView(),new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOnApplyWindowInsetsListener((view,insets)->{
            int top,bottom;
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.R){
                Insets safe=insets.getInsets(WindowInsets.Type.statusBars()
                    |WindowInsets.Type.navigationBars()
                    |WindowInsets.Type.displayCutout());
                top=safe.top;
                bottom=safe.bottom;
            }else{
                top=insets.getSystemWindowInsetTop();
                bottom=insets.getSystemWindowInsetBottom();
            }
            if(view.getPaddingTop()!=top||view.getPaddingBottom()!=bottom){
                view.setPadding(0,top,0,bottom);
            }
            return insets;
        });
        return root;
    }
    private void openSection(String section){
        final String file;
        switch(section){
            case "dance": file="Dance_Atlas_English.html";break;
            case "poses": file="POSE_Atlas_English.html";break;
            case "costumes": file="Costume_Atlas_English.html";break;
            case "home": file="index.html";break;
            default: return;
        }
        ui.post(()->{
            if(web!=null&&!isFinishing()&&!isDestroyed()){
                Log.i("AtlasNavigation","Opening "+section);
                web.loadUrl("https://"+HOST+"/assets/"+file);
            }
        });
    }
    private WebView makeWebView(){
        WebView w=new WebView(this);
        web=w;
        w.setBackgroundColor(Color.rgb(15,17,27));
        WebSettings settings=w.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        w.addJavascriptInterface(new GalleryBridge(),"AtlasNative");
        w.setWebViewClient(new AtlasClient());
        w.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> callback,FileChooserParams params){
                if(videoSelection!=null)videoSelection.onReceiveValue(null);
                videoSelection=callback;
                try{startActivityForResult(params.createIntent(),PICK_VIDEO);return true;}
                catch(Exception error){videoSelection=null;callback.onReceiveValue(null);toast("Unable to open your videos");return true;}
            }
        });
        w.setDownloadListener((url,ua,disposition,mime,length)->{
            if(approved(url))saveImage(url,URLUtil.guessFileName(url,disposition,mime));
            else toast("Image source not approved");
        });
        return w;
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==PICK_VIDEO&&videoSelection!=null){
            Uri[] uris=WebChromeClient.FileChooserParams.parseResult(result,data);
            videoSelection.onReceiveValue(uris);
            videoSelection=null;
        }
    }
    private class GalleryBridge{
        @JavascriptInterface public void saveImage(String url,String name){AtlasActivity.this.saveImage(url,name);}
        @JavascriptInterface public void openSection(String section){AtlasActivity.this.openSection(section);}
    }
    private static boolean approved(String source){
        try{
            URL u=new URL(source);
            return "https".equalsIgnoreCase(u.getProtocol())&&HOSTS.contains(u.getHost().toLowerCase(Locale.ROOT));
        }catch(Exception e){return false;}
    }
    private void toast(String msg){ui.post(()->{
        if(!isFinishing()&&!isDestroyed())Toast.makeText(AtlasActivity.this,msg,Toast.LENGTH_LONG).show();
    });}
    private static String imageName(String name,String url){
        String base=name==null?"":name.trim();
        if(base.isEmpty()){
            try{base=new URL(url).getPath().replaceFirst("^.*/","");}
            catch(Exception e){base="";}
        }
        base=base.replaceAll("[^A-Za-z0-9._-]","_");
        if(base.isEmpty()||base.startsWith("."))base="AtlasImage.png";
        if(base.length()>100)base=base.substring(base.length()-100);
        if(!base.matches("(?i).+\\.(png|jpe?g|webp)"))base+=".png";
        return base;
    }
    private void saveImage(String source,String name){
        if(!approved(source)){toast("Unapproved image source");return;}
        worker.execute(()->{
            Uri inserted=null;HttpURLConnection conn=null;
            try{
                conn=(HttpURLConnection)new URL(source).openConnection();
                conn.setConnectTimeout(15000);conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent","Mozilla/5.0 AtlasEnglish/6.0 Android");
                conn.setInstanceFollowRedirects(true);
                conn.connect();
                if(conn.getResponseCode()!=200)throw new IOException("HTTP "+conn.getResponseCode());
                if(!approved(conn.getURL().toString()))throw new IOException("Unexpected image redirect");
                String type=conn.getContentType();
                if(type!=null)type=type.split(";",2)[0].trim().toLowerCase(Locale.ROOT);
                if(type==null||type.equals("application/octet-stream")){
                    if(source.toLowerCase(Locale.ROOT).endsWith(".webp"))type="image/webp";
                    else if(source.toLowerCase(Locale.ROOT).matches(".*\\.jpe?g$"))type="image/jpeg";
                    else type="image/png";
                }
                if(!type.equals("image/png")&&!type.equals("image/jpeg")&&!type.equals("image/webp"))throw new IOException("Invalid image type");
                ContentValues values=new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME,imageName(name,source));
                values.put(MediaStore.Images.Media.MIME_TYPE,type);
                values.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/Atlas English");
                values.put(MediaStore.Images.Media.IS_PENDING,1);
                ContentResolver resolver=getContentResolver();
                inserted=resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),values);
                if(inserted==null)throw new IOException("Couldn't create Gallery image");
                try(InputStream in=conn.getInputStream();OutputStream out=resolver.openOutputStream(inserted,"w")){
                    if(out==null)throw new IOException("Couldn't save image");
                    byte[] buf=new byte[16384];int n;long total=0;
                    while((n=in.read(buf))!=-1){out.write(buf,0,n);total+=n;if(total>40*1024*1024)throw new IOException("Image exceeds maximum size");}
                    if(total<512)throw new IOException("Image empty");
                }
                values.clear();values.put(MediaStore.Images.Media.IS_PENDING,0);
                resolver.update(inserted,values,null,null);inserted=null;
                toast("Saved to Pictures / Atlas English");
            }catch(Exception error){
                if(inserted!=null){try{getContentResolver().delete(inserted,null,null);}catch(Exception ignored){}}
                toast("Save failed: "+error.getMessage());
            }finally{if(conn!=null)conn.disconnect();}
        });
    }
    private class AtlasClient extends WebViewClient{
        private boolean navigation(String url){
            if(url==null)return true;
            Uri uri=Uri.parse(url);
            if("atlasdownload".equalsIgnoreCase(uri.getScheme())){
                saveImage(uri.getQueryParameter("src"),uri.getQueryParameter("name"));
                return true;
            }
            if("https".equalsIgnoreCase(uri.getScheme())&&HOST.equals(uri.getHost()))return false;
            if("https".equalsIgnoreCase(uri.getScheme())){
                try{startActivity(new Intent(Intent.ACTION_VIEW,uri));}
                catch(Exception e){toast("No browser installed");}
                return true;
            }
            return true;
        }
        @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return navigation(r.getUrl().toString());}
        @Override public boolean shouldOverrideUrlLoading(WebView v,String url){return navigation(url);}
        @Override public void onPageFinished(WebView view,String url){
            super.onPageFinished(view,url);
            Log.i("AtlasNavigation","Loaded "+url);
        }
        @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest request){
            Uri uri=request.getUrl();
            if(!"https".equals(uri.getScheme())||!HOST.equals(uri.getHost()))return null;
            String path=Uri.decode(uri.getPath());
            if(path==null||!path.startsWith("/assets/"))return null;
            String item=path.substring(8);
            if(item.isEmpty()||item.contains("..")||item.contains("\\")||item.startsWith("/"))return null;
            try{
                InputStream source=getAssets().open(item);
                return new WebResourceResponse(mime(item),"UTF-8",source);
            }catch(IOException e){
                return new WebResourceResponse("text/plain","UTF-8",404,"Not Found",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));
            }
        }
        @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail){
            try{
                if(view.getParent() instanceof ViewGroup)((ViewGroup)view.getParent()).removeView(view);
                view.destroy();
                if(rendererRecovery++<1){setContentView(makeRootView());web.loadUrl(HOME);}
                else{
                    TextView fallback=new TextView(AtlasActivity.this);
                    fallback.setText("3D renderer stopped. Close and reopen Atlas to return to the gallery chooser.");
                    fallback.setTextSize(18);fallback.setPadding(35,60,35,35);
                    setContentView(fallback);
                }
            }catch(Exception ignored){}
            return true;
        }
    }
    private static String mime(String path){
        String s=path.toLowerCase(Locale.ROOT);
        if(s.endsWith(".html"))return "text/html";
        if(s.endsWith(".js"))return "text/javascript";
        if(s.endsWith(".css"))return "text/css";
        if(s.endsWith(".png"))return "image/png";
        if(s.endsWith(".jpg")||s.endsWith(".jpeg"))return "image/jpeg";
        if(s.endsWith(".webp"))return "image/webp";
        if(s.endsWith(".svg"))return "image/svg+xml";
        return "application/octet-stream";
    }
    @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
    @Override protected void onDestroy(){
        if(videoSelection!=null){videoSelection.onReceiveValue(null);videoSelection=null;}
        worker.shutdownNow();if(web!=null)try{web.destroy();}catch(Exception e){}
        super.onDestroy();
    }
}

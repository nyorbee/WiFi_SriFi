.class public Lpl/nyorbee/bssid/MainActivity;
.super Landroid/app/Activity;

.field private web:Landroid/webkit/WebView;

.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Landroid/app/Activity;-><init>()V
    return-void
.end method

.method protected onCreate(Landroid/os/Bundle;)V
    .registers 7
    invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V

    new-instance v0, Landroid/webkit/WebView;
    invoke-direct {v0, p0}, Landroid/webkit/WebView;-><init>(Landroid/content/Context;)V
    iput-object v0, p0, Lpl/nyorbee/bssid/MainActivity;->web:Landroid/webkit/WebView;
    const v3, -0xeeebe8
    invoke-virtual {v0, v3}, Landroid/webkit/WebView;->setBackgroundColor(I)V

    invoke-virtual {v0}, Landroid/webkit/WebView;->getSettings()Landroid/webkit/WebSettings;
    move-result-object v1
    const/4 v2, 0x1
    invoke-virtual {v1, v2}, Landroid/webkit/WebSettings;->setJavaScriptEnabled(Z)V
    invoke-virtual {v1, v2}, Landroid/webkit/WebSettings;->setDomStorageEnabled(Z)V

    new-instance v1, Lpl/nyorbee/bssid/WifiBridge;
    invoke-direct {v1, p0}, Lpl/nyorbee/bssid/WifiBridge;-><init>(Landroid/content/Context;)V
    const-string v2, "Android"
    invoke-virtual {v0, v1, v2}, Landroid/webkit/WebView;->addJavascriptInterface(Ljava/lang/Object;Ljava/lang/String;)V

    const-string v3, "file:///android_asset/index.html"
    invoke-virtual {v0, v3}, Landroid/webkit/WebView;->loadUrl(Ljava/lang/String;)V
    invoke-virtual {p0, v0}, Landroid/app/Activity;->setContentView(Landroid/view/View;)V

    # Android 6+: poproś o lokalizację (bez niej system nie poda BSSID)
    sget v2, Landroid/os/Build$VERSION;->SDK_INT:I
    const/16 v3, 0x17
    if-lt v2, v3, :cond_skip
    const-string v2, "android.permission.ACCESS_FINE_LOCATION"
    invoke-virtual {p0, v2}, Landroid/app/Activity;->checkSelfPermission(Ljava/lang/String;)I
    move-result v3
    if-eqz v3, :cond_skip
    const/4 v3, 0x2
    new-array v3, v3, [Ljava/lang/String;
    const/4 v4, 0x0
    aput-object v2, v3, v4
    const-string v2, "android.permission.ACCESS_COARSE_LOCATION"
    const/4 v4, 0x1
    aput-object v2, v3, v4
    const/4 v4, 0x0
    invoke-virtual {p0, v3, v4}, Landroid/app/Activity;->requestPermissions([Ljava/lang/String;I)V
    :cond_skip
    return-void
.end method

# Otwiera systemowy wybór pliku do importu listy AP
.method public pickTxt()V
    .registers 3
    new-instance v0, Landroid/content/Intent;
    const-string v1, "android.intent.action.OPEN_DOCUMENT"
    invoke-direct {v0, v1}, Landroid/content/Intent;-><init>(Ljava/lang/String;)V
    const-string v1, "android.intent.category.OPENABLE"
    invoke-virtual {v0, v1}, Landroid/content/Intent;->addCategory(Ljava/lang/String;)Landroid/content/Intent;
    const-string v1, "*/*"
    invoke-virtual {v0, v1}, Landroid/content/Intent;->setType(Ljava/lang/String;)Landroid/content/Intent;
    const/16 v1, 0x2a
    invoke-virtual {p0, v0, v1}, Landroid/app/Activity;->startActivityForResult(Landroid/content/Intent;I)V
    return-void
.end method

# Wczytuje wybrany plik i przekazuje tekst do importTxt() na stronie
.method protected onActivityResult(IILandroid/content/Intent;)V
    .registers 8
    invoke-super {p0, p1, p2, p3}, Landroid/app/Activity;->onActivityResult(IILandroid/content/Intent;)V
    const/16 v0, 0x2a
    if-ne p1, v0, :cond_ret
    const/4 v0, -0x1
    if-ne p2, v0, :cond_ret
    if-eqz p3, :cond_ret

    :try_start_0
    invoke-virtual {p3}, Landroid/content/Intent;->getData()Landroid/net/Uri;
    move-result-object v0
    invoke-virtual {p0}, Landroid/app/Activity;->getContentResolver()Landroid/content/ContentResolver;
    move-result-object v1
    invoke-virtual {v1, v0}, Landroid/content/ContentResolver;->openInputStream(Landroid/net/Uri;)Ljava/io/InputStream;
    move-result-object v0
    new-instance v1, Ljava/util/Scanner;
    const-string v2, "UTF-8"
    invoke-direct {v1, v0, v2}, Ljava/util/Scanner;-><init>(Ljava/io/InputStream;Ljava/lang/String;)V
    const-string v2, "\\A"
    invoke-virtual {v1, v2}, Ljava/util/Scanner;->useDelimiter(Ljava/lang/String;)Ljava/util/Scanner;
    move-result-object v1
    invoke-virtual {v1}, Ljava/util/Scanner;->next()Ljava/lang/String;
    move-result-object v1
    invoke-virtual {v0}, Ljava/io/InputStream;->close()V
    invoke-static {v1}, Lorg/json/JSONObject;->quote(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1
    new-instance v2, Ljava/lang/StringBuilder;
    invoke-direct {v2}, Ljava/lang/StringBuilder;-><init>()V
    const-string v3, "importTxt("
    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v2, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    const-string v3, ")"
    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v1
    iget-object v2, p0, Lpl/nyorbee/bssid/MainActivity;->web:Landroid/webkit/WebView;
    const/4 v3, 0x0
    invoke-virtual {v2, v1, v3}, Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V
    :try_end_0
    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :catch_0

    :cond_ret
    return-void

    :catch_0
    move-exception v0
    iget-object v1, p0, Lpl/nyorbee/bssid/MainActivity;->web:Landroid/webkit/WebView;
    const-string v2, "importTxt(null)"
    const/4 v3, 0x0
    invoke-virtual {v1, v2, v3}, Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V
    return-void
.end method

package app.basa

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val ComponentActivity.store by preferencesDataStore("basa_settings")
private val ink = Color(0xFF17211F)
private val green = Color(0xFF176B55)
private val canvas = Color(0xFFF6F8F5)
private enum class Provider(val label: String, val base: String) {
    Groq("Groq", "https://api.groq.com/openai/v1"), OpenRouter("OpenRouter", "https://openrouter.ai/api/v1")
}
private data class Message(val user: Boolean, val text: String)

class MainActivity : ComponentActivity() {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var incoming by mutableStateOf<Uri?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incoming = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getParcelableExtra(Intent.EXTRA_STREAM)
        setContent { BasaApp(incoming, ::recognize, ::copyUriName) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        incoming = intent.takeIf { it.action == Intent.ACTION_SEND }?.getParcelableExtra(Intent.EXTRA_STREAM)
    }
    private fun copyUriName(uri: Uri): String = contentResolver.query(uri, null, null, null, null)?.use {
        val i = it.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (it.moveToFirst() && i >= 0) it.getString(i) else "Screenshot"
    } ?: "Screenshot"
    private fun recognize(uri: Uri, done: (String?, String?) -> Unit) {
        try {
            recognizer.process(InputImage.fromFilePath(this, uri))
                .addOnSuccessListener { result -> done(result.text, null) }
                .addOnFailureListener { done(null, it.localizedMessage ?: "Could not read this image") }
        } catch (e: Exception) { done(null, e.localizedMessage ?: "Could not open this image") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BasaApp(sharedUri: Uri?, recognize: (Uri, (String?, String?) -> Unit) -> Unit, uriName: (Uri) -> String) {
    val activity = LocalContext.current as ComponentActivity
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    var page by remember { mutableStateOf("home") }
    var image by remember { mutableStateOf<Uri?>(null) }
    var imageLabel by remember { mutableStateOf("") }
    var ocr by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf(Provider.Groq) }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var models by remember { mutableStateOf(listOf<String>()) }
    var configMessage by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<Message>() }
    fun selectImage(uri: Uri) {
        image = uri; imageLabel = uriName(uri); ocr = ""; error = ""; messages.clear(); scanning = true
        recognize(uri) { text, issue -> ocr = text.orEmpty(); error = issue.orEmpty(); scanning = false }
    }
    LaunchedEffect(sharedUri) { sharedUri?.let(::selectImage) }
    LaunchedEffect(Unit) {
        val prefs = activity.store.data.first()
        provider = runCatching { Provider.valueOf(prefs[stringPreferencesKey("provider")] ?: "Groq") }.getOrDefault(Provider.Groq)
        apiKey = runCatching { Crypto.decrypt(prefs[stringPreferencesKey("key_${provider.name}")] ?: "") }.getOrDefault("")
        model = prefs[stringPreferencesKey("model_${provider.name}")] ?: ""
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let(::selectImage) }
    val colors = if (dark) darkColorScheme(primary = Color(0xFF8DD4B8), background = Color(0xFF111B17), surface = Color(0xFF1B2722), onBackground = Color(0xFFE2E8E3), onSurface = Color(0xFFE2E8E3)) else lightColorScheme(primary = green, background = canvas, surface = Color.White, onBackground = ink, onSurface = ink)
    MaterialTheme(colorScheme = colors) {
        Scaffold(containerColor = colors.background, topBar = {
            TopAppBar(navigationIcon = { if (page == "chat") IconButton(onClick={page="home"}) { Icon(Icons.Default.ArrowBack, "Back to screenshot") } }, title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BasaMark(Modifier.size(34.dp)); Text("Basa", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                }
            }, actions = {
                IconButton(onClick = { page = if (page == "settings") "home" else "settings" }) {
                    Icon(if (page == "settings") Icons.Default.Close else Icons.Default.Settings, contentDescription = if (page == "settings") "Close settings" else "Settings")
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = canvas))
        }) { pad ->
            if (page == "settings") SettingsPage(Modifier.padding(pad), provider, apiKey, model, models, loading, configMessage,
                { next -> provider = next; scope.launch { val p=activity.store.data.first(); apiKey=runCatching{Crypto.decrypt(p[stringPreferencesKey("key_${next.name}")]?: "")}.getOrDefault(""); model=p[stringPreferencesKey("model_${next.name}")]?: ""; models= emptyList() } },
                { apiKey=it }, { model=it }, { models=it },
                { scope.launch { testConnection(provider, apiKey, { loading=it }, { status, found -> configMessage=status; models=found }) } },
                { scope.launch { activity.store.edit { it[stringPreferencesKey("provider")]=provider.name; it[stringPreferencesKey("key_${provider.name}")]=Crypto.encrypt(apiKey); it[stringPreferencesKey("model_${provider.name}")]=model }; configMessage="Configuration saved on this device" } })
            else if (page == "chat") ChatPage(Modifier.padding(pad), messages, input, { input=it }, loading, { clipboard.setText(AnnotatedString(it)) }, {
                val question=input.trim(); if (question.isNotEmpty() && !loading) {
                    messages.add(Message(true, question)); input=""
                    scope.launch { sendChat(provider, apiKey, model, ocr, messages.toList(), { loading=it }, { answer, issue -> if(answer!=null) messages.add(Message(false,answer)); if(issue!=null) error=issue }) }
                }
            }, error)
            else HomePage(Modifier.padding(pad), image, imageLabel, ocr, scanning, error,
                { picker.launch("image/*") }, { ocr=it }, { clipboard.setText(AnnotatedString(ocr)) }, { messages.clear(); error=""; page="chat" })
        }
    }
}

@Composable
private fun BasaMark(modifier: Modifier = Modifier) {
    Image(painter = painterResource(R.drawable.basa_logo), contentDescription = "Basa logo", modifier = modifier)
}

@Composable
private fun HomePage(modifier: Modifier, image: Uri?, name: String, text: String, scanning: Boolean, error: String, pick: () -> Unit, edit: (String)->Unit, copy: ()->Unit, ask: ()->Unit) {
    val context = LocalContext.current
    val bitmap = remember(image) { image?.let { runCatching { android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver, it)) }.getOrNull() } }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp, vertical=8.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("Read anything. Ask anything.", fontSize=24.sp, fontWeight=FontWeight.Bold)
        Text("Share a screenshot or choose one to extract its text.", color=Color(0xFF586560))
        Button(onClick=pick, modifier=Modifier.fillMaxWidth().heightIn(min=54.dp), shape=RoundedCornerShape(16.dp)) { Icon(Icons.Default.AddPhotoAlternate, null); Spacer(Modifier.width(8.dp)); Text("Import screenshot") }
        bitmap?.let { Image(bitmap=it.asImageBitmap(), contentDescription=name, modifier=Modifier.fillMaxWidth().heightIn(max=200.dp).clip(RoundedCornerShape(18.dp))) }
        if (image != null || scanning || text.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                Text("Extracted text", fontWeight=FontWeight.SemiBold, fontSize=18.sp)
                TextButton(onClick=copy, enabled=text.isNotEmpty()) { Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("Copy all") }
            }
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(error.isNotBlank()) Text(error, color=MaterialTheme.colorScheme.error)
            OutlinedTextField(value=text, onValueChange=edit, modifier=Modifier.fillMaxWidth().heightIn(min=170.dp), placeholder={ Text(if(scanning) "Scanning screenshot…" else "Extracted text will appear here") }, shape=RoundedCornerShape(16.dp))
            Button(onClick=ask, enabled=text.isNotBlank(), modifier=Modifier.fillMaxWidth().heightIn(min=54.dp), shape=RoundedCornerShape(16.dp)) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Ask AI about this") }
        }
    }
}

@Composable
private fun ChatPage(modifier: Modifier, messages: List<Message>, input: String, onInput:(String)->Unit, loading:Boolean, copy:(String)->Unit, send:()->Unit, error:String) {
    Column(modifier.fillMaxSize().padding(horizontal=16.dp)) {
        Text("Chat", fontSize=24.sp, fontWeight=FontWeight.Bold, modifier=Modifier.padding(vertical=8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding=PaddingValues(vertical=8.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(messages.isEmpty()) item { Text("Your extracted screenshot text is ready as context. What would you like to know?", color=Color(0xFF586560), modifier=Modifier.padding(8.dp)) }
            itemsIndexed(messages) { _, m ->
                Column(Modifier.fillMaxWidth().padding(start=if(m.user) 48.dp else 0.dp, end=if(m.user) 0.dp else 24.dp).clip(RoundedCornerShape(16.dp)).background(if(m.user) Color(0xFFE2F1E9) else MaterialTheme.colorScheme.surface).padding(14.dp)) {
                    Text(markdownText(m.text), lineHeight=24.sp)
                    if(!m.user) TextButton(onClick={copy(m.text)}, contentPadding=PaddingValues(top=4.dp)) { Icon(Icons.Default.ContentCopy,null,Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("Copy response") }
                }
            }
            if(loading) item { Row(verticalAlignment=Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth=2.dp); Spacer(Modifier.width(10.dp)); Text("Thinking…", color=Color(0xFF586560)) } }
        }
        if(error.isNotBlank()) Text(error, color=MaterialTheme.colorScheme.error, modifier=Modifier.padding(8.dp))
        Row(verticalAlignment=Alignment.Bottom, horizontalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.padding(vertical=10.dp)) {
            OutlinedTextField(value=input, onValueChange=onInput, modifier=Modifier.weight(1f), placeholder={Text("Ask a follow-up…")}, shape=RoundedCornerShape(18.dp), maxLines=5)
            FilledIconButton(onClick=send, enabled=input.isNotBlank() && !loading, modifier=Modifier.size(52.dp)) { Icon(Icons.Default.Send, "Send message") }
        }
    }
}

private fun markdownText(source: String): AnnotatedString = buildAnnotatedString {
    val pattern = Regex("\\*\\*(.+?)\\*\\*|\\x60([^\\x60]+)\\x60|(?<!\\*)\\*([^*]+)\\*(?!\\*)")
    var cursor = 0
    pattern.findAll(source).forEach { match ->
        append(source.substring(cursor, match.range.first))
        when {
            match.groups[1] != null -> pushStyle(SpanStyle(fontWeight = FontWeight.Bold)).also { append(match.groups[1]!!.value); pop() }
            match.groups[2] != null -> pushStyle(SpanStyle(background = Color(0x1F777777))).also { append(match.groups[2]!!.value); pop() }
            else -> pushStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)).also { append(match.groups[3]!!.value); pop() }
        }
        cursor = match.range.last + 1
    }
    append(source.substring(cursor))
}

@Composable
private fun SettingsPage(modifier:Modifier, provider:Provider, key:String, model:String, models:List<String>, loading:Boolean, status:String, select:(Provider)->Unit, setKey:(String)->Unit, setModel:(String)->Unit, setModels:(List<String>)->Unit, test:()->Unit, save:()->Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("AI settings", fontSize=24.sp, fontWeight=FontWeight.Bold)
        Text("Your key is encrypted on this device and sent only to the selected provider.", color=Color(0xFF586560))
        Text("Provider", fontWeight=FontWeight.SemiBold)
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) { Provider.entries.forEach { p -> FilterChip(selected=provider==p, onClick={select(p)}, label={Text(p.label)}) } }
        OutlinedTextField(value=key, onValueChange=setKey, modifier=Modifier.fillMaxWidth(), label={Text("API key")}, placeholder={Text("Paste your ${provider.label} key")}, singleLine=true, visualTransformation=PasswordVisualTransformation(), keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password), shape=RoundedCornerShape(14.dp))
        OutlinedTextField(value=model, onValueChange=setModel, modifier=Modifier.fillMaxWidth(), label={Text("Model ID")}, placeholder={Text("e.g. llama-3.3-70b-versatile")}, singleLine=true, shape=RoundedCornerShape(14.dp))
        if(models.isNotEmpty()) { Text("Available models", fontWeight=FontWeight.SemiBold); models.take(40).forEach { id -> TextButton(onClick={setModel(id)}, contentPadding=PaddingValues(horizontal=4.dp)) { Text(id, color=if(id==model) green else ink) } } }
        Button(onClick=test, enabled=!loading && key.isNotBlank(), modifier=Modifier.fillMaxWidth().heightIn(min=52.dp), shape=RoundedCornerShape(14.dp)) { if(loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth=2.dp) else Text("Test connection & load models") }
        Button(onClick=save, modifier=Modifier.fillMaxWidth().heightIn(min=52.dp), shape=RoundedCornerShape(14.dp)) { Text("Save configuration") }
        if(status.isNotBlank()) Text(status, color=if(status.contains("fail",true)||status.contains("enter",true)) MaterialTheme.colorScheme.error else green)
    }
}

private val http = OkHttpClient()
private suspend fun testConnection(provider:Provider,key:String,progress:(Boolean)->Unit,result:(String,List<String>)->Unit) {
    if(key.isBlank()) { result("Enter an API key first", emptyList()); return }
    progress(true)
    try { val ids=withContext(Dispatchers.IO) { val req=Request.Builder().url("${provider.base}/models").header("Authorization","Bearer $key").get().build(); http.newCall(req).execute().use { r -> if(!r.isSuccessful) error("Connection failed (${r.code}): ${r.body?.string()?.take(250)}"); val arr=JSONObject(r.body!!.string()).optJSONArray("data")?:JSONArray(); (0 until arr.length()).mapNotNull{arr.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank)}.sorted() } }; result("Connected. ${ids.size} models available.",ids) }
    catch(e:Exception) { result("Connection failed: ${e.localizedMessage ?: "request error"}",emptyList()) } finally { progress(false) }
}

private suspend fun sendChat(provider:Provider,key:String,model:String,context:String,history:List<Message>,progress:(Boolean)->Unit,result:(String?,String?)->Unit) {
    if(key.isBlank()) { result(null,"Add an API key in Settings first."); progress(false); return }
    if(model.isBlank()) { result(null,"Choose or enter a model in Settings first."); progress(false); return }
    progress(true)
    try { val answer=withContext(Dispatchers.IO) {
        val msgs=JSONArray().put(JSONObject().put("role","system").put("content",if(context.isBlank()) "Answer clearly and helpfully." else "Answer clearly and helpfully. The user shared this screenshot text as context:\n\n$context"))
        history.forEach { msgs.put(JSONObject().put("role",if(it.user) "user" else "assistant").put("content",it.text)) }
        val body=JSONObject().put("model",model).put("messages",msgs).put("temperature",0.3).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val req=Request.Builder().url("${provider.base}/chat/completions").header("Authorization","Bearer $key").post(body).build()
        http.newCall(req).execute().use { r -> val raw=r.body?.string().orEmpty(); if(!r.isSuccessful) error("Request failed (${r.code}): ${runCatching{JSONObject(raw).optJSONObject("error")?.optString("message")}.getOrNull()?:raw.take(250)}"); JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content") }
    }; result(answer,null) } catch(e:Exception) { result(null,e.localizedMessage?:"Could not contact the AI provider") } finally { progress(false) }
}

private object Crypto {
    private const val alias="basa_api_key"
    private fun key():SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (ks.getKey(alias,null) as? SecretKey)?.let{return it}
        val gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return gen.generateKey()
    }
    fun encrypt(value:String):String { if(value.isEmpty()) return ""; val c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key()); return Base64.encodeToString(c.iv+c.doFinal(value.toByteArray()),Base64.NO_WRAP) }
    fun decrypt(value:String):String { if(value.isEmpty()) return ""; val raw=Base64.decode(value,Base64.NO_WRAP); val c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,raw.copyOfRange(0,12))); return String(c.doFinal(raw.copyOfRange(12,raw.size))) }
}

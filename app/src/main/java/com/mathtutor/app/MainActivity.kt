package com.mathtutor.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var question: EditText
    private lateinit var server: EditText
    private lateinit var result: TextView
    private var imageBase64: String? = null
    private val pickCode = 100
    private val cameraCode = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 22, 22, 22)
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
        root.addView(TextView(this).apply {
            text = "Math Tutor"
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = "Matematika – postupno rješavanje na hrvatskom"
            textSize = 15f
        })
        question = EditText(this).apply {
            hint = "Upiši zadatak (ili dodaj fotografiju)"
            minLines = 3
            gravity = android.view.Gravity.TOP
        }
        root.addView(question)
        val photos = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        photos.addView(Button(this).apply {
            text = "Fotografiraj"
            setOnClickListener {
                try { startActivityForResult(Intent("android.media.action.IMAGE_CAPTURE"), cameraCode) }
                catch (_: Exception) { message("Kamera nije dostupna.") }
            }
        })
        photos.addView(Button(this).apply {
            text = "Iz galerije"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }, pickCode)
            }
        })
        root.addView(photos)
        root.addView(TextView(this).apply {
            text = "AI poslužitelj (HTTPS adresa)"
            textSize = 14f
        })
        server = EditText(this).apply {
            hint = "https://tvoj-posluzitelj.example"
            setSingleLine(true)
            setText(getPreferences(MODE_PRIVATE).getString("server", ""))
        }
        root.addView(server)
        root.addView(Button(this).apply {
            text = "Riješi zadatak"
            setOnClickListener { solve() }
        })
        result = TextView(this).apply {
            text = "Upiši zadatak ili odaberi fotografiju."
            textSize = 17f
            setTextIsSelectable(true)
        }
        root.addView(result)
    }

    private fun message(text: String) = runOnUiThread { result.text = text }

    private fun setImage(bitmap: Bitmap) {
        val factor = minOf(1f, 1600f / maxOf(bitmap.width, bitmap.height))
        val scaled = if (factor < 1f)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * factor).toInt(), (bitmap.height * factor).toInt(), true)
            else bitmap
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        imageBase64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        message("Fotografija je dodana. Pritisni Riješi zadatak.")
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        try {
            if (requestCode == pickCode) {
                val uri = data?.data ?: return
                contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)?.let { setImage(it) }
                }
            } else if (requestCode == cameraCode) {
                (data?.extras?.get("data") as? Bitmap)?.let { setImage(it) }
                    ?: message("Kamera nije vratila sliku.")
            }
        } catch (e: Exception) { message("Ne mogu otvoriti fotografiju: ${e.message}") }
    }

    private fun solve() {
        val base = server.text.toString().trim().trimEnd('/')
        val task = question.text.toString().trim()
        if (!base.startsWith("https://")) {
            message("Unesi HTTPS adresu AI poslužitelja.")
            return
        }
        if (task.isEmpty() && imageBase64 == null) {
            message("Upiši zadatak ili dodaj fotografiju.")
            return
        }
        getPreferences(MODE_PRIVATE).edit().putString("server", base).apply()
        message("Rješavam zadatak...")
        thread {
            var connection: HttpURLConnection? = null
            try {
                connection = URL("$base/solve").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 20000
                connection.readTimeout = 120000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val payload = JSONObject().put("question", task).put("image_base64", imageBase64 ?: "")
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    .bufferedReader().use { it.readText() }
                if (status !in 200..299) throw Exception("HTTP $status: ${response.take(250)}")
                val obj = JSONObject(response)
                val sb = StringBuilder()
                sb.append(obj.optString("title", "Rješenje")).append("\n\n")
                sb.append(obj.optString("recognized", "")).append("\n\n")
                val steps = obj.optJSONArray("steps")
                if (steps != null) {
                    for (i in 0 until steps.length()) {
                        val step = steps.opt(i)
                        sb.append(i + 1).append(". ")
                        sb.append(if (step is JSONObject) step.optString("explanation", step.toString()) else step.toString())
                        sb.append("\n\n")
                    }
                }
                sb.append("Odgovor: ").append(obj.optString("answer", ""))
                message(sb.toString())
            } catch (e: Exception) {
                message("Greška: ${e.message}\nProvjeri AI poslužitelj i internetsku vezu.")
            } finally { connection?.disconnect() }
        }
    }
}

package com.github.alphafoxz.ff14mobileact

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.ComponentActivity.RESULT_OK
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat.startActivity
import com.github.alphafoxz.ff14mobileact.ui.theme.FF14MobileActTheme
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

private val gamePackage = "com.tencent.tmgp.fmgame"

class MainActivity : ComponentActivity() {
    private var vpnIntent: Intent? = null
    private val vpnLauncher =
        registerForActivityResult(VpnActivityResultContract()) { result ->
            run {
                if (result) {
                    vpnIntent = Intent(this, NetService::class.java)
                    startService(vpnIntent)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val lp = WindowManager.LayoutParams(
            140, 140,
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or   // 初始穿透
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }

        setContent {
            FF14MobileActTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    StartButton(
                        context = applicationContext,
                        lp = lp,
                        packageManager = packageManager,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
        vpnLauncher.launch("")

    }

    override fun onDestroy() {
        super.onDestroy()
        if (vpnIntent != null) {
            stopService(vpnIntent)
            vpnIntent = null
        }
    }
}

@Composable
fun StartButton(
    context: Context,
    lp: LayoutParams,
    packageManager: PackageManager,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = {
            launchGame(context, lp, packageManager)
        },
        content = {
            Text("开始游戏")
        },
        modifier = modifier
    )
}

//@Preview(showBackground = true)
//@Composable
//fun GreetingPreview() {
//    FF14MobileActTheme {
//        StartButton()
//    }
//}

private fun launchGame(
    context: Context,
    lp: LayoutParams,
    packageManager: PackageManager,
) {
    // a. 先让按钮“可点”，再立刻切回穿透
    lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
//    windowManager.updateViewLayout(button, lp)

    // b. 构造 Intent
    val launch = packageManager.getLaunchIntentForPackage(gamePackage)
    if (launch != null) {
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(context, launch, null)
    } else {
//        val uri = "market://details?id=$gameId".toUri()
//        startActivity(
//            context,
//            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
//            null
//        )
        Toast.makeText(context, "未从安装列表中找到FF14，请确认已经安装了游戏", Toast.LENGTH_SHORT)
            .show()
    }

    // c. 恢复穿透
//    lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
//    windowManager.updateViewLayout(button, lp)
}

class NetService : VpnService() {
    private var tunFd: ParcelFileDescriptor? = null
    private lateinit var worker: Thread

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        tunFd = super.Builder()
            .addAddress("192.168.0.1", 24)
            .addRoute("0.0.0.0", 0)
//            .addAllowedApplication(gamePackage)
            .addDnsServer("192.168.1.1").establish()
        tunFd ?: return START_NOT_STICKY

        // 自己的套接字必须调用 protect()，否则死循环
        val fd = tunFd?.fileDescriptor ?: return START_NOT_STICKY
        worker = Thread { pumpTun(fd) }.also { it.start() }

//        startForeground(NOTI_ID, buildNotification())
        return START_STICKY
    }

    private fun pumpTun(tun: FileDescriptor) {
        val input = FileInputStream(tun)
        val out = FileOutputStream(tun)
        val buffer = ByteArray(32767)
        while (!Thread.interrupted()) {
            val len = input.read(buffer)
            println("读 $len 长度报文")
            if (len > 0) {
                // TODO: 解析 IP/TCP/UDP 头，重定向、转发或记录
                out.write(buffer, 0, len);
            }
        }

    }

    override fun onDestroy() {
        worker.interrupt()
        tunFd?.close()
    }
}

class VpnActivityResultContract : ActivityResultContract<String, Boolean>() {
    override fun getSynchronousResult(
        context: Context,
        input: String
    ): SynchronousResult<Boolean>? {
        // 已授权，无需弹系统页，立即返回 true
        return if (VpnService.prepare(context) == null) {
            SynchronousResult(true)
        } else null              // 继续正常流程
    }

    override fun createIntent(context: Context, input: String): Intent {
        return VpnService.prepare(context)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
        if (resultCode != RESULT_OK) {
            return false
        }
        return true
    }
}

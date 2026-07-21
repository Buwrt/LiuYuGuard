package com.liuyuguard.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liuyuguard.base.LiuYuApplication
import com.liuyuguard.model.RunMode
import com.liuyuguard.ui.components.LoadingPlaceholder
import com.liuyuguard.ui.navigation.AppNavigation
import com.liuyuguard.ui.theme.LiuYuGuardTheme
import com.liuyuguard.util.PermissionChecker
import com.liuyuguard.util.ShizukuDetector

/** Shizuku授权请求码 */
private const val SHIZUKU_REQUEST_CODE = 9001

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        (application as LiuYuApplication).recheckRunMode()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestBasicPermissions()

        setContent {
            val app = application as LiuYuApplication
            val runMode by app.runMode.collectAsStateWithLifecycle()
            val isChecking by app.isPermissionChecking.collectAsStateWithLifecycle()
            val isDarkTheme by app.isDarkTheme.collectAsStateWithLifecycle()

            LiuYuGuardTheme(darkTheme = isDarkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when {
                        isChecking -> LoadingPlaceholder(message = "正在检测运行环境权限…")
                        runMode.isFunctional -> AppNavigation(runMode = runMode)
                        else -> PermissionDeniedScreen()
                    }
                }
            }
        }
    }

    private fun requestBasicPermissions() {
        val missing = PermissionChecker.getMissingPermissions(this)
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SHIZUKU_REQUEST_CODE) {
            (application as LiuYuApplication).recheckRunMode()
        }
    }
}

@Composable
private fun PermissionDeniedScreen() {
    val app = LocalContext.current.applicationContext as LiuYuApplication
    val shizukuAvailable = remember { ShizukuDetector.isShizukuAvailable() }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Security,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(72.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "权限不足",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "流御守护需要Root权限或Shizuku权限才能运行流量管控功能。\n\n" +
                    "Root权限：完整功能，高精度内核级流量统计与管控\n" +
                    "Shizuku权限：基础功能，弱精度流量管控",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (shizukuAvailable) {
            Button(
                onClick = {
                    val activity = LocalContext.current as? ComponentActivity
                    activity?.let { ShizukuDetector.requestPermission(it, SHIZUKU_REQUEST_CODE) }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(Icons.Default.VerifiedUser, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("通过Shizuku授权")
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(
                onClick = { app.recheckRunMode() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("已授权，重新检测")
            }
        } else {
            Text(
                text = "未检测到Root或Shizuku环境，请先配置权限后重试。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedButton(
                onClick = { app.recheckRunMode() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("重新检测")
            }
        }
    }
}
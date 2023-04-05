package com.sanmer.mrepo.ui.activity.install

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.sanmer.mrepo.app.isNotReady
import com.sanmer.mrepo.app.isSucceeded
import com.sanmer.mrepo.provider.EnvProvider
import com.sanmer.mrepo.provider.SuProvider
import com.sanmer.mrepo.ui.theme.AppTheme
import com.sanmer.mrepo.viewmodel.InstallViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import java.io.File

class InstallActivity : ComponentActivity() {
    private val viewModel by viewModels<InstallViewModel>()

    init {
        Timber.d("InstallActivity init")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        SuProvider.state.onEach {
            if (it.isNotReady) {
                viewModel.send("SuProvider init")
                SuProvider.init(this)
            }
            if (it.isSucceeded && EnvProvider.event.isNotReady) {
                viewModel.send("EnvProvider init")
                EnvProvider.init()
            }
        }.launchIn(lifecycleScope)

        EnvProvider.state.onEach {
            if (it.isSucceeded) {
                val uri = intent.data
                if (uri != null) {
                    viewModel.install(this, uri)
                } else {
                    viewModel.state.setFailed("The uri is null!")
                }
            }
        }.launchIn(lifecycleScope)

        setContent {
            AppTheme {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides this
                ) {
                    InstallScreen()
                }
            }
        }
    }

    companion object {
        fun start(context: Context, uri: Uri) {
            val intent = Intent(context, InstallActivity::class.java).apply {
                flags =  Intent.FLAG_ACTIVITY_NEW_TASK
                data = uri
            }
            context.startActivity(intent)
        }

        fun start(context: Context, path: File) = start(context, path.toUri())
    }
}
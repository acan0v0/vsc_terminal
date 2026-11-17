package com.acan0v0.vscterminal

import android.app.Application
import android.util.Log
import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class VscTerminalApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installBouncyCastle()
    }

    /**
     * Android 自带的 "BC" 是裁剪过的 BouncyCastle，没有 X25519 / Ed25519，
     * sshj 在密钥交换阶段会直接抛 `no such algorithm: X25519 for provider BC`。
     *
     * 这里在本进程内用随 APK 打包的完整版 BC 替换掉它（不影响系统进程）；
     * 随后让 sshj 继续用名字 "BC" 查找算法。
     */
    private fun installBouncyCastle() {
        val current = Security.getProvider("BC")
        if (current != null && current.javaClass.name == BouncyCastleProvider::class.java.name) return

        try {
            Security.removeProvider("BC")
            val position = Security.addProvider(BouncyCastleProvider())
            SecurityUtils.setSecurityProvider("BC")
            val installed = Security.getProvider("BC")
            if (position < 0 || installed == null) {
                Log.e(TAG, "BouncyCastle 安装失败：position=$position provider=$installed")
            } else {
                Log.i(TAG, "已替换为完整版 BouncyCastle：${installed.javaClass.name}")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "BouncyCastle 安装异常", t)
        }
    }

    private companion object {
        const val TAG = "VscTerminalApp"
    }
}

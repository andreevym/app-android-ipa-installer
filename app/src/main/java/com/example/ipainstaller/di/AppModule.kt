package com.example.ipainstaller.di

import android.content.Context
import android.hardware.usb.UsbManager
import com.example.ipainstaller.usb.AppleDeviceDetector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideUsbManager(@ApplicationContext context: Context): UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    @Provides
    @Singleton
    fun provideAppleDeviceDetector(
        @ApplicationContext context: Context,
        usbManager: UsbManager,
    ): AppleDeviceDetector = AppleDeviceDetector(context, usbManager)
}

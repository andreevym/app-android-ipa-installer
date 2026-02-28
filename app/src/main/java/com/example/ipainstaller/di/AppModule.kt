package com.example.ipainstaller.di

import android.content.ContentResolver
import android.content.Context
import android.hardware.usb.UsbManager
import androidx.room.Room
import com.example.ipainstaller.data.AppDatabase
import com.example.ipainstaller.data.InstallHistoryDao
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
    fun provideContentResolver(@ApplicationContext context: Context): ContentResolver =
        context.contentResolver

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

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "ipa_installer.db")
            .build()

    @Provides
    fun provideInstallHistoryDao(database: AppDatabase): InstallHistoryDao =
        database.installHistoryDao()
}

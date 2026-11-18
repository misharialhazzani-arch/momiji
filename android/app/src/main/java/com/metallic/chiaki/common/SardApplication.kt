// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** سرد: the app speaks Arabic unless its language was chosen in the system's settings */
class SardApplication : Application()
{
	override fun onCreate()
	{
		super.onCreate()
		if(AppCompatDelegate.getApplicationLocales().isEmpty)
			AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ar"))
	}
}

package com.localgithub.githost.ui

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * ZXing's stock CaptureActivity is declared landscape-only in the library's
 * manifest. This subclass exists so the manifest can declare it portrait.
 */
class PortraitCaptureActivity : CaptureActivity()

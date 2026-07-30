import re

from django.conf import settings
from django.http import Http404, HttpResponse, JsonResponse
from django.views.decorators.http import require_GET


ROOM_CODE = re.compile(r"^[A-Z0-9]{6}$")


@require_GET
def room_link(request, code):
    code = code.upper()
    if not ROOM_CODE.fullmatch(code):
        raise Http404
    app_link = f"bazikhooneh://room/{code}"
    html = f"""<!doctype html>
<html lang="fa" dir="rtl">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>دعوت به اتاق بازی‌خونه</title>
  <meta name="description" content="پیوستن به اتاق {code} در بازی‌خونه">
  <meta property="og:title" content="دعوت به اتاق بازی‌خونه">
  <meta property="og:description" content="کد اتاق: {code}">
  <style>
    body{{font-family:sans-serif;max-width:36rem;margin:3rem auto;padding:1rem;
    background:#f5f7fb;color:#182033;text-align:center}}
    main{{background:white;padding:2rem;border-radius:1rem}}
    a{{display:block;padding:1rem;background:#1769aa;color:white;
    border-radius:.75rem;text-decoration:none;font-weight:bold}}
    code{{font-size:1.5rem;letter-spacing:.2rem}}
  </style>
</head>
<body><main>
  <h1>دعوت به اتاق بازی‌خونه</h1>
  <p>کد اتاق: <code>{code}</code></p>
  <p><a href="{app_link}">باز کردن در برنامه</a></p>
  <p>اگر برنامه خودکار باز نشد، دکمه بالا را انتخاب کنید.</p>
</main></body></html>"""
    return HttpResponse(html)


@require_GET
def asset_links(request):
    fingerprints = [
        item.strip()
        for item in settings.ANDROID_APP_CERT_SHA256.split(",")
        if item.strip()
    ]
    payload = [{
        "relation": ["delegate_permission/common.handle_all_urls"],
        "target": {
            "namespace": "android_app",
            "package_name": "ir.codelighthouse.bazikhooneh",
            "sha256_cert_fingerprints": fingerprints,
        },
    }] if fingerprints else []
    return JsonResponse(payload, safe=False)

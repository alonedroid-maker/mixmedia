#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0601
#endif
#ifndef WINVER
#define WINVER 0x0601
#endif
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif

#include <windows.h>
#include <wchar.h>

static void ShowErrorBox(const wchar_t* message) {
    MessageBoxW(NULL, message, L"MediaX", MB_OK | MB_ICONERROR);
}

int WINAPI wWinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, PWSTR pCmdLine, int nCmdShow) {
    (void)hInstance;
    (void)hPrevInstance;
    (void)nCmdShow;

    wchar_t exePath[MAX_PATH];
    DWORD len = GetModuleFileNameW(NULL, exePath, MAX_PATH);
    if (len == 0 || len >= MAX_PATH) {
        ShowErrorBox(L"Could not locate MediaX installation directory.");
        return 1;
    }

    wchar_t appDir[MAX_PATH];
    wcsncpy(appDir, exePath, MAX_PATH - 1);
    appDir[MAX_PATH - 1] = L'\0';
    wchar_t* lastSlash = wcsrchr(appDir, L'\\');
    if (lastSlash != NULL) {
        *lastSlash = L'\0';
    }

    wchar_t javawPath[MAX_PATH];
    swprintf(javawPath, MAX_PATH, L"%ls\\runtime\\bin\\javaw.exe", appDir);
    if (GetFileAttributesW(javawPath) == INVALID_FILE_ATTRIBUTES) {
        ShowErrorBox(L"Bundled Java 11 runtime was not found (runtime\\bin\\javaw.exe). Please reinstall MediaX.");
        return 2;
    }

    wchar_t cmdLine[8192];
    const wchar_t* extraArgs = (pCmdLine != NULL && pCmdLine[0] != L'\0') ? pCmdLine : L"";
    swprintf(
        cmdLine,
        8192,
        L"\"%ls\" -Xms64m -Xmx768m -Dfile.encoding=UTF-8 -Djdk.tls.client.protocols=TLSv1.2,TLSv1.3 "
        L"-Dsun.java2d.noddraw=true \"-Dapp.home=%ls\" -cp \"%ls\\lib\\*\" com.example.desktop.MainKt %ls",
        javawPath,
        appDir,
        appDir,
        extraArgs
    );

    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    si.cb = sizeof(si);
    ZeroMemory(&pi, sizeof(pi));

    BOOL ok = CreateProcessW(
        javawPath,
        cmdLine,
        NULL,
        NULL,
        FALSE,
        CREATE_NO_WINDOW,
        NULL,
        appDir,
        &si,
        &pi
    );

    if (!ok) {
        ShowErrorBox(L"Failed to start MediaX Desktop runtime.");
        return 3;
    }

    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return 0;
}

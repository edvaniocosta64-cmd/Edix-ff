package com.example.shizuku;

interface IShizukuShellService {
    void destroy();
    int getUid();
    String runShellCommand(String command);
}

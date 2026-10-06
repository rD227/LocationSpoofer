# PocketSwap 1.0.0

把存储空间作为低优先级后备 swap，面向小内存 Android 手机。默认 2048 MiB，分为四个 512 MiB 文件，保留至少 1024 MiB 可用存储。保持现有 zRAM、内存回收设置、CPU 频率和 SELinux 策略；没有应用保活或定位/步态模拟功能。

## 安装与状态

在 Magisk、KernelSU 或 APatch 的模块页面选择 `pocketswap-v1.0.0.zip`，正常重启，解锁主用户一次。点击模块“操作”查看状态；管理器不支持操作按钮时，使用 root 终端：

```sh
su -c 'sh /data/adb/modules/pocketswap/pocketswap.sh status'
```

`/proc/swaps` 是实际启用情况的依据。日志和状态在 `/data/adb/pocketswap/runtime.log`、`status.txt`。容量单位为 MiB；四个文件的可用交换容量略小于 2 GiB，因为存在交换头。

模块只使用 `service.sh`，不安装全局 `service.d` 脚本，不需要 Zygisk、LSPosed、系统目录挂载或 KernelSU 的挂载元模块。安装模块不会更新 LocationSpoofer APK。

安装器识别我们此前创建的启动脚本的精确 SHA-256，并将其备份、移出启动目录，再接管 `/data/misc_ce/0/locationspoofer_swap/fallback[0-3].swap`。文件须为 root 所有的四个 512 MiB 普通文件；不会重新初始化已存在的文件。其他同名脚本不修改，日志会提醒避免多个 swap 模块同时运行。现有交换文件不立即停用；模块将在下次启动后接管。

## Root 与系统兼容性

| 环境 | 处理与限制 |
| --- | --- |
| Magisk | 使用官方模块安装与 late_start service 接口；本机验证环境为 Magisk 30.7、Android 11、4.19 内核。 |
| KernelSU / KernelSU Next 等分支 | 使用通用脚本模块接口；兼容管理器提供的 BusyBox 路径。不同分支需要实机验证。晚加载也可运行 service.sh，但执行时机由管理器决定。 |
| APatch | 使用通用模块接口及 APatch BusyBox 路径；尚未在 APatch 实机验证。 |
| 漏洞获得的临时 Android root | ZIP 不能凭空增加模块管理器或开机执行能力。可将完整源码解压到可信目录，以真正的 root 手动运行 `sh /可信绝对路径/pocketswap.sh start`；重启失去 root 后不会自动恢复。 |
| 虚拟空间、仅应用内 root、受限制的 UID 0 | 可能无法控制宿主内核；实际 UID、CAP_SYS_ADMIN、/proc 和 swapon 检查不通过时停止。 |
| iOS 越狱 | 不支持；这是 Android/Linux 模块。 |

安装器要求 Android 8/API 26 以上、管理器内安装、root 及可读 `/proc/swaps`。API 版本满足要求不等于内核支持。运行时还需要实际 CAP_SYS_ADMIN、允许 swap 操作的 SELinux 上下文，以及系统工具或管理器 BusyBox 提供所需命令。拒绝操作时保留日志，不自动改成 permissive，也不尝试绕过 root 管理器授权。缺少工具时会停止；原版 Android toolbox 的能力不代表所有 ROM 都一样。

当前实机验证限于 Redmi 4X、LineageOS/AOSP 11、F2FS、Magisk 的命令环境及小文件启用/停用测试；KernelSU/APatch 的兼容性基于官方接口设计，不能视为已经实测所有管理器、ROM 或一次完整开机流程。

## 文件系统和加密

**不限于 F2FS。** ext4 等文件系统也能支持 swap 文件，但需要内核启用 swap、文件系统实现支持、文件布局合适及操作权限。`fallocate` 在部分文件系统/内核上仍可能形成不适合 swap 的布局；不能只看文件系统名称。模块先用 4 MiB 小文件执行 `mkswap → swapon → swapoff`，通过后逐个创建大文件。已有文件直接尝试启用，失败保留并记录原因。某些内核可限制 swap 区域数量，过多分块也可能失败。

Btrfs 有 NOCOW 等额外要求，本模块不会替用户修改这些属性；tmpfs 等内存文件系统不适合作为磁盘后备 swap。为避免在 2 GB 手机上重现大规模写零导致的内存/I/O 压力，本版没有 `dd` 回退，也不自动创建 loop/dm-crypt 或改分区；小文件探测失败时停止，即使另一个创建方法可能可用。

FBE 手机默认使用主用户的 `/data/misc_ce/0/pocketswap`，首次解锁后启动；旧配置迁移使用上文路径。FDE 手机使用 `/data/adb/pocketswap/fde-swap`，启动完成后启用。未知/未加密存储默认拒绝，可显式设置 `ALLOW_UNENCRYPTED=1` 使用 `/data/adb/pocketswap/plain-swap`。目录为 root 700，文件为 root 600。

**这些路径和权限不构成交换数据加密保证。** fscrypt 的文件加密不能直接当作 swap 加密：部分旧内核允许启用但绕过文件加密，新内核可能直接拒绝；具有块层加密的设备还取决于实际块设备路径。真正的加密 swap 需要适当的 dm-crypt 或 loop 等独立方案，本版没有实现。`ALLOW_UNENCRYPTED` 仅控制未知/未加密存储的选址策略。交换页可能含应用敏感数据；删除文件也不等于安全擦除闪存内容。

## 配置与容量修改

编辑 `/data/adb/pocketswap/config.conf`，格式与 `config.example` 一致，使用 LF 换行、无空格整数。配置作为数据解析，不执行其中的 shell 内容。

| 项 | 默认 | 约束 |
| --- | --- | --- |
| SIZE_MB | 2048 | 64–8192，必须能被 CHUNK_MB 整除；大容量不保证更快。 |
| CHUNK_MB | 512 | 64–512；为本机推荐保持 512。 |
| PRIORITY | 1 | 0–32757；自动调整到现有 zRAM 以下，检查实际生效值。 |
| MIN_FREE_MB | 1024 | 256–32768；每次分配均检查存储余量。 |
| ALLOW_UNENCRYPTED | 0 | 0 或 1，仅控制存储选址，见加密说明。 |

调整大小前：禁用模块，重启、解锁，然后执行下方 purge；修改配置后重新启用并重启。模块不会在线覆盖、缩小或重新格式化活动交换文件。部分分块失败时保留已成功启用的文件，状态显示 `partial`；根据日志处理后可再次启动，不会因此关闭 zRAM。

```sh
su -c 'sh /data/adb/modules/pocketswap/pocketswap.sh purge'
```

不要同时运行其他更改 zRAM 优先级或接管同一交换文件的模块。zRAM 优先级在本模块启动后被其他程序改变，不会由本模块持续纠正。默认配置不是防杀进程承诺：Android LMKD 仍可能根据压力、抖动等条件终止应用，存储 swap 也比 RAM 慢，并会增加闪存写入。

## 禁用和卸载

禁用后重启生效；禁用按钮不会立即撤销活动 swap。卸载时只有确认 swap 使用量为零才执行停用，再删除本模块所有的文件。若文件里仍有交换页、主用户未解锁、或其他操作仍在执行，则保留文件和恢复脚本，避免瞬间把大量数据搬回 RAM。

卸载后如日志显示需要清理：先重启（确保模块已删除/禁用），解锁主用户，然后执行：

```sh
su -c 'sh /data/adb/pocketswap/recovery/pocketswap.sh purge'
```

清理只处理所有权标记匹配的专用目录中的预定文件，不执行 `swapoff -a`，不删除应用数据。状态、配置及小体积恢复脚本保留，便于排障；交换文件删除后这些文件不再占用 2 GB。若已有启动操作持有锁，先等它退出；重启后仍出现无效锁时，确认没有 PocketSwap 操作运行，再删除 `/data/adb/pocketswap/run.lock` 中的 pid/boot 文件与空锁目录。不要手动删除 `/proc/swaps` 中仍列出的文件。

## 许可证与构建

Copyright (C) 2026 xvsu。PocketSwap 源码按 GNU General Public License version 3（GPL-3.0-only）发布，无担保。`LICENSE` 是用户提供的完整 GNU GPL v3 文本，逐字节保留。发布 ZIP 同时包含完整模块 shell 源码、配置示例和许可证；修改后分发应遵循该许可证。

仓库中使用 Python 3 构建：`python tools/build_pocketswap.py`。产物在 `build/pocketswap/`，ZIP 内容直接位于根目录，无外层文件夹；脚本 LF 换行，UNIX 执行权限，固定时间戳，可重复构建。源码测试：在 POSIX shell 环境运行 `sh modules/pocketswap/tests/test_common.sh modules/pocketswap/common.sh` 和 `sh modules/pocketswap/tests/test_installer.sh modules/pocketswap`；共 64 项检查，仅模拟 swap 操作，不分配真实交换空间，也不修改设备路径。

参考：[Magisk 模块指南](https://topjohnwu.github.io/Magisk/guides.html)、[KernelSU 模块指南](https://kernelsu.org/guide/module.html)、[APatch 模块指南](https://apatch.dev/apm-guide.html)、[swapon 文件布局限制](https://man7.org/linux/man-pages/man8/swapon.8.html)、[Linux fscrypt 限制](https://www.kernel.org/doc/html/next/filesystems/fscrypt.html)、[Android LMKD](https://source.android.com/docs/core/perf/lmkd)。

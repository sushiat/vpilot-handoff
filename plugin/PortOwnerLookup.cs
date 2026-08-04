using System;
using System.Diagnostics;
using System.Runtime.InteropServices;

namespace Handoff.Plugin
{
    /// <summary>
    /// Looks up which process currently owns a bound TCP/UDP port (issue #98's port-conflict
    /// dialog), via the IP Helper API (iphlpapi.dll) -- the same underlying data `netstat -ano`
    /// reads, but structured and without shelling out to a subprocess (avoids locale-dependent
    /// text parsing, and a vPilot plugin quietly spawning netstat.exe, which some AV heuristics
    /// flag). No admin rights needed: GetExtendedTcpTable/GetExtendedUdpTable are available to
    /// any user, same as Resource Monitor's "listening ports" view.
    /// </summary>
    internal static class PortOwnerLookup
    {
        private const int AfInet = 2; // IPv4 -- matches HandoffWebSocketServer/HandoffDiscoveryListener, which only ever bind 0.0.0.0.
        private const int TcpTableOwnerPidAll = 5;
        private const int UdpTableOwnerPid = 1;

        [StructLayout(LayoutKind.Sequential)]
        private struct MibTcpRowOwnerPid
        {
            public uint State;
            public uint LocalAddr;
            public byte LocalPort1;
            public byte LocalPort2;
            public byte LocalPort3;
            public byte LocalPort4;
            public uint RemoteAddr;
            public byte RemotePort1;
            public byte RemotePort2;
            public byte RemotePort3;
            public byte RemotePort4;
            public uint OwningPid;

            // Network byte order (big-endian) across the 4-byte field -- only the first two
            // bytes carry the actual port number.
            public int LocalPort => (LocalPort1 << 8) + LocalPort2;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct MibUdpRowOwnerPid
        {
            public uint LocalAddr;
            public byte LocalPort1;
            public byte LocalPort2;
            public byte LocalPort3;
            public byte LocalPort4;
            public uint OwningPid;

            public int LocalPort => (LocalPort1 << 8) + LocalPort2;
        }

        [DllImport("iphlpapi.dll", SetLastError = true)]
        private static extern uint GetExtendedTcpTable(IntPtr tcpTable, ref int size, bool sort, int ipVersion, int tableClass, uint reserved);

        [DllImport("iphlpapi.dll", SetLastError = true)]
        private static extern uint GetExtendedUdpTable(IntPtr udpTable, ref int size, bool sort, int ipVersion, int tableClass, uint reserved);

        /// <summary>Returns "ProcessName (PID 1234)" for whichever process currently owns
        /// <paramref name="port"/>, or null if it can't be determined -- nothing's bound there
        /// anymore, the table lookup failed, or the owning process has already exited by the
        /// time Process.GetProcessById runs (all just means the dialog omits this detail, never
        /// worth failing the whole dialog over).</summary>
        public static string TryDescribeOwner(int port, bool tcp)
        {
            try
            {
                var pid = tcp ? FindTcpOwnerPid(port) : FindUdpOwnerPid(port);
                if (pid == null) return null;

                using (var process = Process.GetProcessById(pid.Value))
                {
                    return process.ProcessName + " (PID " + pid.Value + ")";
                }
            }
            catch
            {
                return null;
            }
        }

        private static int? FindTcpOwnerPid(int port)
        {
            var buffer = IntPtr.Zero;
            try
            {
                var size = 0;
                GetExtendedTcpTable(IntPtr.Zero, ref size, true, AfInet, TcpTableOwnerPidAll, 0);
                if (size <= 0) return null;

                buffer = Marshal.AllocHGlobal(size);
                if (GetExtendedTcpTable(buffer, ref size, true, AfInet, TcpTableOwnerPidAll, 0) != 0) return null;

                var rowCount = Marshal.ReadInt32(buffer);
                var rowsStart = IntPtr.Add(buffer, sizeof(int));
                var rowSize = Marshal.SizeOf(typeof(MibTcpRowOwnerPid));
                for (var i = 0; i < rowCount; i++)
                {
                    var row = (MibTcpRowOwnerPid)Marshal.PtrToStructure(IntPtr.Add(rowsStart, i * rowSize), typeof(MibTcpRowOwnerPid));
                    if (row.LocalPort == port) return (int)row.OwningPid;
                }
                return null;
            }
            finally
            {
                if (buffer != IntPtr.Zero) Marshal.FreeHGlobal(buffer);
            }
        }

        private static int? FindUdpOwnerPid(int port)
        {
            var buffer = IntPtr.Zero;
            try
            {
                var size = 0;
                GetExtendedUdpTable(IntPtr.Zero, ref size, true, AfInet, UdpTableOwnerPid, 0);
                if (size <= 0) return null;

                buffer = Marshal.AllocHGlobal(size);
                if (GetExtendedUdpTable(buffer, ref size, true, AfInet, UdpTableOwnerPid, 0) != 0) return null;

                var rowCount = Marshal.ReadInt32(buffer);
                var rowsStart = IntPtr.Add(buffer, sizeof(int));
                var rowSize = Marshal.SizeOf(typeof(MibUdpRowOwnerPid));
                for (var i = 0; i < rowCount; i++)
                {
                    var row = (MibUdpRowOwnerPid)Marshal.PtrToStructure(IntPtr.Add(rowsStart, i * rowSize), typeof(MibUdpRowOwnerPid));
                    if (row.LocalPort == port) return (int)row.OwningPid;
                }
                return null;
            }
            finally
            {
                if (buffer != IntPtr.Zero) Marshal.FreeHGlobal(buffer);
            }
        }
    }
}

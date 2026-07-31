using System.IO;
using System.Text;

namespace Handoff.Plugin
{
    /// <summary>
    /// Path.Combine drop-in that never silently discards earlier segments when a later one
    /// looks rooted (a drive letter, or a leading separator) -- that's Path.Combine's real,
    /// documented behavior, and the source of every CodeQL cs/path-combine finding in this
    /// codebase (issue #51). Every current call site only ever joins plain relative literals,
    /// so that behavior never actually triggers today -- but a future call site combining a
    /// value from outside this process (a device ID, a file name out of an API response, ...)
    /// could hit it for real, silently reading/writing the wrong path. Cheap to have the safe
    /// primitive ready before that happens rather than after.
    ///
    /// Ports System.IO.Path.Join's own algorithm (.NET Core 2.1+ / .NET Standard 2.1 --
    /// unavailable on this project's net48 target) verbatim: skip null/empty segments, and
    /// insert exactly one separator between two segments unless either side already ends/starts
    /// with one (verified against dotnet/runtime's Path.cs and PathInternal.IsDirectorySeparator).
    /// </summary>
    public static class PathJoin
    {
        public static string Combine(params string[] segments)
        {
            var builder = new StringBuilder();
            foreach (var segment in segments)
            {
                if (string.IsNullOrEmpty(segment)) continue;

                if (builder.Length == 0)
                {
                    builder.Append(segment);
                    continue;
                }

                if (!IsDirectorySeparator(builder[builder.Length - 1]) && !IsDirectorySeparator(segment[0]))
                {
                    builder.Append(Path.DirectorySeparatorChar);
                }
                builder.Append(segment);
            }
            return builder.ToString();
        }

        private static bool IsDirectorySeparator(char c) => c == Path.DirectorySeparatorChar || c == Path.AltDirectorySeparatorChar;
    }
}

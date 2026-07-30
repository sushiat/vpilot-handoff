using System;
using System.Collections.Generic;
using System.Globalization;

namespace Handoff.Plugin
{
    /// <summary>
    /// Extracts a human station name (e.g. "Bremen Radar") from a controller's own ATIS/info
    /// lines (the VATSIM data feed's "text_atis", see VatsimControllerInfo), when there's one to
    /// confidently extract -- this beats vatspy's composed name (VatSpyStationNaming) when it
    /// parses cleanly, since it's the controller's own live self-description, not a generic
    /// place+suffix composition. Null whenever nothing can be extracted with confidence; callers
    /// fall back to VatSpyStationNaming in that case.
    ///
    /// Patterns below are drawn from a live scan of the feed (2026-07-29, ~80 online controllers,
    /// 56 with text_atis set) -- not a formal spec, since text_atis is free-form pilot/controller-
    /// authored text with no schema at all. Confirmed patterns:
    ///   - Most commonly just the clean name on its own ("Praha Radar", "Vancouver Centre").
    ///   - " - ", " | ", and " / " (space-padded) are real separators before boilerplate
    ///     ("Langen Radar - CPDLC [EDGS]", "Ruzyne Ground | PDC available [LKPR]", "Zagreb Radar
    ///     / CDDLC - LDZO").
    ///   - Some vACCs (e.g. Austria) run the logon-code boilerplate straight into the name with no
    ///     separator at all -- "Wien Radar CPDLC/DCL LOWA", "Wien Tower DCL LOWW". These are cut at
    ///     the first word that IS (or "/"-combines) one of the known logon-code keywords (CPDLC,
    ///     DCL, PDC), not a separator character.
    ///   - A bare "-" with NO surrounding spaces is part of the name itself, not a separator
    ///     ("Krasnoyarsk-Control", "Surgyt-Approach") -- splitting on any "-" would wrongly
    ///     truncate these.
    ///   - Sometimes quoted ("\"Muscat Control\" - CPDLC [MUSN] - Solo").
    ///   - Occasionally prefixed with a literal "Callsign " label before the real name
    ///     ("Callsign HAMBURG TOWER - PDC/DCL Logon EDDH").
    ///   - Some lines are ALL CAPS ("LUEBECK TOWER") -- title-cased for display consistency with
    ///     vatspy's own composed names.
    ///   - Some lines are just chat/joke text with no station name in them at all, or a name
    ///     buried past a "Callsign"-less preamble with no dash/pipe to split on ("its \"Lindbergh
    ///     Tower\" NOT \"san diego tower\"", "Events, Discord, Feed back and more! at") -- the
    ///     word-count cap and role-word-suffix check below reject these rather than emit noise.
    /// </summary>
    public static class VatAtisStationNameExtractor
    {
        // Real station names are almost always two words ("Bremen Radar", "Heathrow Tower"); one
        // extra word of headroom (e.g. "Seoul Departure/Approach") catches nearly every legit
        // case without letting multi-clause chat/joke text through -- a plain word count turned
        // out to separate real names from stray text-atis noise more reliably than a character
        // length cap did.
        private const int MaxNameWords = 3;
        private const string CallsignPrefixLabel = "Callsign ";

        // Logon-code boilerplate keywords that some vACCs run straight into the name with no
        // separator character at all ("Wien Radar CPDLC/DCL LOWA"). Checked as a whole word (or
        // "/"-joined combo of these) rather than substring, so airport codes/names never collide.
        private static readonly string[] BoilerplateKeywords = { "CPDLC", "DCL", "PDC" };

        // A real station name -- controller-authored or vatspy-composed -- always ends with one
        // of these role words (confirmed against every clean example in the live scan above).
        // This single check is what actually separates "Ruzyne Ground" from stray chat text: it's
        // a strong, well-supported signal, not a guess.
        private static readonly string[] RoleWords =
        {
            "Tower", "Ground", "Delivery", "Approach", "Departure",
            "Control", "Centre", "Center", "Radar", "Radio", "Apron", "Clearance"
        };

        public static string Extract(IReadOnlyList<string> textAtis)
        {
            if (textAtis == null || textAtis.Count == 0) return null;
            var line = textAtis[0]?.Trim();
            if (string.IsNullOrEmpty(line)) return null;

            var quoted = ExtractQuoted(line);
            var candidate = quoted ?? SplitAtSeparator(StripCallsignLabel(line));
            candidate = TruncateAtBoilerplateKeyword(candidate);
            candidate = candidate?.Trim().TrimEnd('.', ' ');

            if (string.IsNullOrEmpty(candidate)) return null;
            var words = candidate.Split((char[])null, StringSplitOptions.RemoveEmptyEntries);
            if (words.Length > MaxNameWords) return null;
            if (!ContainsRoleWord(words)) return null;

            return NormalizeCase(candidate);
        }

        private static string StripCallsignLabel(string line) =>
            line.StartsWith(CallsignPrefixLabel, StringComparison.OrdinalIgnoreCase)
                ? line.Substring(CallsignPrefixLabel.Length)
                : line;

        /// <summary>If the line opens with a quote character, returns the content up to the matching close quote; else null.</summary>
        private static string ExtractQuoted(string line)
        {
            if (line.Length == 0) return null;
            var open = line[0];
            char close;
            switch (open)
            {
                case '"': close = '"'; break;
                case '\'': close = '\''; break;
                case '“': close = '”'; break; // curly “ ”
                default: return null;
            }

            var end = line.IndexOf(close, 1);
            return end > 1 ? line.Substring(1, end - 1) : null;
        }

        /// <summary>Cuts at the earliest of " - ", " | ", " / ", or "." -- whichever comes first -- else returns the line unchanged.</summary>
        private static string SplitAtSeparator(string line)
        {
            var cut = -1;
            foreach (var separator in new[] { " - ", " | ", " / ", "." })
            {
                var index = line.IndexOf(separator, StringComparison.Ordinal);
                if (index >= 0 && (cut < 0 || index < cut)) cut = index;
            }
            return cut >= 0 ? line.Substring(0, cut) : line;
        }

        /// <summary>Cuts before the first word that is (or "/"-combines) a logon-code boilerplate keyword; else returns the line unchanged.</summary>
        private static string TruncateAtBoilerplateKeyword(string line)
        {
            if (string.IsNullOrEmpty(line)) return line;
            var words = line.Split((char[])null, StringSplitOptions.RemoveEmptyEntries);
            for (var i = 0; i < words.Length; i++)
            {
                foreach (var part in words[i].Split('/'))
                {
                    if (Array.Exists(BoilerplateKeywords, k => string.Equals(k, part, StringComparison.OrdinalIgnoreCase)))
                    {
                        return string.Join(" ", words, 0, i);
                    }
                }
            }
            return line;
        }

        // Checks every word, not just the last -- a role word doesn't always land at the very end
        // ("Brussels Ground North", a sub-position qualifier tacked on after it, the same
        // convention VATGlasses sub-sector positions use). Each word is checked with a plain
        // suffix match (not exact-equals) so a direct letter-joined compound like "Eurocontrol"
        // still counts, not just hyphen-joined ones like "Krasnoyarsk-Control" -- there's no
        // realistic English word that coincidentally ends in "Tower"/"Ground"/"Control"/etc.
        // without meaning it, so this only costs false negatives, not false positives. Safe to
        // scan the whole candidate rather than just the last word specifically because it's
        // already capped at MaxNameWords by the caller.
        private static bool ContainsRoleWord(string[] words)
        {
            foreach (var word in words)
            {
                foreach (var roleWord in RoleWords)
                {
                    if (word.EndsWith(roleWord, StringComparison.OrdinalIgnoreCase)) return true;
                }
            }
            return false;
        }

        /// <summary>ALL-CAPS lines get title-cased for display consistency with vatspy's composed names; anything already mixed-case is left untouched.</summary>
        private static string NormalizeCase(string candidate)
        {
            foreach (var c in candidate)
            {
                if (char.IsLower(c)) return candidate;
            }
            return CultureInfo.InvariantCulture.TextInfo.ToTitleCase(candidate.ToLowerInvariant());
        }
    }
}

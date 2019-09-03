#if NETSTANDARD1_0 || NETSTANDARD1_1 || NETSTANDARD1_2 || NETSTANDARD1_3 || NETSTANDARD1_4 || NETSTANDARD1_5 || NETSTANDARD1_6 || NETCOREAPP1_0 || NETCOREAPP1_1
#define NETSTANDARD_BELOW_2_0
#endif

#if NETCOREAPP1_0 || NETCOREAPP1_1 || NETCOREAPP2_0 || NETCOREAPP2_1
#define NETSTANDARD_LEAST_1_0
#endif

// Unreachable code
#pragma warning disable CS0162

using System;
using System.Collections.Generic;
using System.IO;
using System.Diagnostics; // For Process class
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;

namespace RTMath.Utilities.ResourceLoaderUtils
{
	// Some AdHoc logging
	internal static class Logger
	{
		public const int DBG = 0;
		public const int INF = 2;
		public const int ERR = 4;
		private static List<StreamWriter> _sinks = new List<StreamWriter>();

		internal static int LogLevel
		{
			get
			{
				return logLevel;
			}
		}

		private static int logLevel = ERR;

		private static void LogLog(string v)
		{
			lock (_sinks)
			{
				string line =
#if !NETSTANDARD_BELOW_2_0 || NETSTANDARD_LEAST_1_0
					$"{Process.GetCurrentProcess().Id:d6} " +
#endif
					$"{DateTime.Now:HH:mm:ss.ffffff}: {v}{(0 == _sinks.Count ? "": "\n")}";
			//yyyy-MM-dd
				if (0 == _sinks.Count)
					Console.WriteLine(line);
				else
					foreach (var sink in _sinks)
						sink.Write(line);
			}
		}

		internal static bool LogLevelLeast(int logLevel)
		{
			return logLevel >= LogLevel;
		}

		internal static void Log(int logLevel, string str)
		{
			if (LogLevelLeast(logLevel))
				LogLog(str);
		}

		internal static void Log(string str)
		{
			if (LogLevelLeast(DBG))
				LogLog(str);
		}

		internal static void AddSink(StreamWriter to)
		{
			if (null == to)
				to = new StreamWriter(Console.OpenStandardOutput());

			to.AutoFlush = true;
			lock (_sinks)
			{
				foreach (var s in _sinks)
					if (s == to)
						return;
				_sinks.Add(to);
			}
		}

		internal static void SetLogLevel(int logLevel)
		{
			Logger.logLevel = logLevel;
		}
	}

	internal static class Util
	{
		private const string ResourceFileTagRegex = "\\[([^@\\]]*)@([^@\\]]*)\\]";

		internal static bool IsAscii(String value)
		{
			return Encoding.UTF8.GetByteCount(value) == value.Length;
		}

		internal static String Dt2Str(DateTime dt)
		{
			return $"{dt:HH:mm:ss.ffffff}";
		}

		internal static String GetTags(String str, Dictionary<string, string> tags)
		{
			Regex regex = new Regex(ResourceFileTagRegex);

			foreach (Match m in regex.Matches(str))
				tags.Add(m.Groups[1].Value, m.Groups[2].Value); // We are guaranteed to have 2 groups in every proper match

			return regex.Replace(str, "");
		}

		public static FileStream TryOpenForWriteTest(string path, FileOptions options = 0)
		{
			// NOTE: This is not the "normal" .NET FileStream on Mono
			try
			{
				return new FileStream(path, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None, 1, options);
			}
			catch
			{
				return null;
			}
		}

		internal static bool TryDeleteFile(string path)
		{
			try
			{
				File.Delete(path);
				return true;
			}
			catch
			{
				return false;
			}
		}
	}

	// A special Dictionary that can print itself to a string that resembles JSON
	internal class Diagnostics
	{
		private class Map : Dictionary<String, Object>
		{
			private String _name;
			private const string Eol = "\n";

			internal void Group()
			{
				bool again;
				do
				{
					again = false;
					foreach (var o in this)
					{
						String key = o.Key;
						int prefixLength = key.IndexOf('.') + 1;
						if (prefixLength == key.Length)
						{
							var subGroup = o.Value as Map;
							if (null == subGroup)
							{
								Remove(key);
								Add(key, subGroup = new Map { _name = (String)o.Value });
								again = true;
							}

							foreach (var o2 in this.ToList())
							{
								String key2 = o2.Key;
								if (key2.StartsWith(key) && key2.Length > prefixLength)
								{
									subGroup.Add(key2.Substring(prefixLength), o2.Value);
									Remove(key2);
									again = true;
								}
							}

							subGroup.Group();
							if (again)
								break;
						}
					}
				} while (again);
			}

			private static StringBuilder AppendValue(StringBuilder sb, Object value)
			{
				sb.Append('"');

				sb = value is DateTime || value is TimeSpan
					? sb.AppendFormat("{0::HH:mm:ss.ffffff}", value)
					: sb.Append(value);

				return sb.Append('"');
			}

			private static StringBuilder AppendKey(StringBuilder sb, String key) => null != key ? AppendValue(sb, key).Append(": ") : sb;

			private static StringBuilder AppendArray(StringBuilder sb, Array value)
			{
				sb.Append("[");
				String separator = "" + Eol;
				foreach (var o in value)
				{
					AppendValue(sb.Append(separator), o);
					separator = "," + Eol;
				}

				return sb.Append(Eol + " ]");
			}

			public StringBuilder WriteTo(StringBuilder sb)
			{
				sb = AppendKey(sb, _name).Append("{");
				String separator = "" + Eol;
				foreach (var o in this.OrderBy(x => x.Key))
				{
					sb.Append(separator);
					separator = "," + Eol;
					var value = o.Value;
					sb = value is Map ? ((Map)value).WriteTo(sb)
						: value is Array ? AppendArray(AppendKey(sb, o.Key), (Array)value)
						: AppendValue(AppendKey(sb, o.Key), value);
				}

				return sb.Append(Eol + "}");
			}

			internal void Clear(string prefix)
			{
				if (prefix == "")
					Clear();
				else
				if (ContainsKey(prefix))
				{
					var o = this[prefix] as Map;
					if (null != o)
					{
						Group();
						o.Clear();
					}
				}
			}
		}

		private readonly Map _dict = new Map();
		private bool _modified;

		public void Clear(String prefix) => _dict.Clear(prefix);

		public Diagnostics Add(String key, Object obj)
		{
			lock (_dict)
			{
				_dict.Remove(key);
				_dict.Add(key, obj);
				_modified = true;
			}

			return this;
		}

		public override string ToString()
		{
			if (_modified)
			{
				_dict.Group();
				_modified = false;
			}

			var sb = new StringBuilder();
			_dict.WriteTo(sb);
			return sb.ToString();
		}
	}
}

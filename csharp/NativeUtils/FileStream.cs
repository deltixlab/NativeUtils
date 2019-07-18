#if NETSTANDARD1_0 || NETSTANDARD1_1 || NETSTANDARD1_2 || NETSTANDARD1_3 || NETSTANDARD1_4 || NETSTANDARD1_5 || NETSTANDARD1_6 || NETCOREAPP1_0 || NETCOREAPP1_1
#define NETSTANDARD_BELOW_2_0
#endif
// Unreachable code
#pragma warning disable CS0162

using System;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;

namespace RTMath.Utilities.ResourceLoaderUtils
{
	using static Logger;
	/*
	 * Very limited FileStream reimplementation created for the sole purpose of circumventing the lack of file locking support in Mono for Linux
	 * NOTE: Only writing and a few other operations are supported
	 * NOTE: Filesizes are represented by Int32 (enough for our particular purpose)
	 */

	internal class FileStream : IDisposable /* Stream // removed due to incompatibility with other tool */
	{
		public static readonly bool Enabled;
		private readonly object _impl;

		public System.IO.FileStream Their => (System.IO.FileStream)_impl;
		public FileStreamImpl Our => (FileStreamImpl)_impl;

		static FileStream()
		{
			Enabled =
				Type.GetType("Mono.Runtime") != null &&
#if NETSTANDARD_BELOW_2_0
			!RuntimeInformation.IsOSPlatform(OSPlatform.Windows);
#else
			(int)Environment.OSVersion.Platform >= 4;
#endif
		}

		public FileStream(string path, FileMode mode, FileAccess access, FileShare share)
		{
			_impl = Enabled ? (object)new FileStreamImpl(path, mode, access, share, 0, FileOptions.None)
				: (object)new System.IO.FileStream(path, mode, access, share);
		}

		//
		// Summary:
		//     Initializes a new instance of the System.IO.FileStream class with the specified
		//     path, creation mode, read/write and sharing permission, and buffer size.
		//
		// Parameters:
		//   path:
		//     A relative or absolute path for the file that the current FileStream object will
		//     encapsulate.
		//
		//   mode:
		//     A constant that determines how to open or create the file.
		//
		//   access:
		//     A constant that determines how the file can be accessed by the FileStream object.
		//     This also determines the values returned by the System.IO.FileStream.CanRead
		//     and System.IO.FileStream.CanWrite properties of the FileStream object. System.IO.FileStream.CanSeek
		//     is true if path specifies a disk file.
		//
		//   share:
		//     A constant that determines how the file will be shared by processes.
		//
		//   bufferSize:
		//     A positive System.Int32 value greater than 0 indicating the buffer size. The
		//     default buffer size is 4096.
		//
		// Exceptions:
		//   T:System.ArgumentNullException:
		//     path is null.
		//
		//   T:System.ArgumentException:
		//     path is an empty string (""), contains only white space, or contains one or more
		//     invalid characters. -or- path refers to a non-file device, such as "con:", "com1:",
		//     "lpt1:", etc. in an NTFS environment.
		//
		//   T:System.NotSupportedException:
		//     path refers to a non-file device, such as "con:", "com1:", "lpt1:", etc. in a
		//     non-NTFS environment.
		//
		//   T:System.ArgumentOutOfRangeException:
		//     bufferSize is negative or zero. -or- mode, access, or share contain an invalid
		//     value.
		//
		//   T:System.IO.FileNotFoundException:
		//     The file cannot be found, such as when mode is FileMode.Truncate or FileMode.Open,
		//     and the file specified by path does not exist. The file must already exist in
		//     these modes.
		//
		//   T:System.IO.IOException:
		//     An I/O error, such as specifying FileMode.CreateNew when the file specified by
		//     path already exists, occurred. -or- The system is running Windows 98 or Windows
		//     98 Second Edition and share is set to FileShare.Delete. -or- The stream has been
		//     closed.
		//
		//   T:System.Security.SecurityException:
		//     The caller does not have the required permission.
		//
		//   T:System.IO.DirectoryNotFoundException:
		//     The specified path is invalid, such as being on an unmapped drive.
		//
		//   T:System.UnauthorizedAccessException:
		//     The access requested is not permitted by the operating system for the specified
		//     path, such as when access is Write or ReadWrite and the file or directory is
		//     set for read-only access.
		//
		//   T:System.IO.PathTooLongException:
		//     The specified path, file name, or both exceed the system-defined maximum length.
		//     For example, on Windows-based platforms, paths must be less than 248 characters,
		//     and file names must be less than 260 characters.
		public FileStream(string path, FileMode mode, FileAccess access, FileShare share, int bufferSize)
		{
			_impl = Enabled ? (object)new FileStreamImpl(path, mode, access, share, bufferSize, FileOptions.None)
				: (object)new System.IO.FileStream(path, mode, access, share, bufferSize);
		}

		//
		// Summary:
		//     Initializes a new instance of the System.IO.FileStream class with the specified
		//     path, creation mode, read/write and sharing permission, the access other FileStreams
		//     can have to the same file, the buffer size, and additional file options.
		//
		// Parameters:
		//   path:
		//     A relative or absolute path for the file that the current FileStream object will
		//     encapsulate.
		//
		//   mode:
		//     A constant that determines how to open or create the file.
		//
		//   access:
		//     A constant that determines how the file can be accessed by the FileStream object.
		//     This also determines the values returned by the System.IO.FileStream.CanRead
		//     and System.IO.FileStream.CanWrite properties of the FileStream object. System.IO.FileStream.CanSeek
		//     is true if path specifies a disk file.
		//
		//   share:
		//     A constant that determines how the file will be shared by processes.
		//
		//   bufferSize:
		//     A positive System.Int32 value greater than 0 indicating the buffer size. The
		//     default buffer size is 4096.
		//
		//   options:
		//     A value that specifies additional file options.
		//
		// Exceptions:
		//   T:System.ArgumentNullException:
		//     path is null.
		//
		//   T:System.ArgumentException:
		//     path is an empty string (""), contains only white space, or contains one or more
		//     invalid characters. -or- path refers to a non-file device, such as "con:", "com1:",
		//     "lpt1:", etc. in an NTFS environment.
		//
		//   T:System.NotSupportedException:
		//     path refers to a non-file device, such as "con:", "com1:", "lpt1:", etc. in a
		//     non-NTFS environment.
		//
		//   T:System.ArgumentOutOfRangeException:
		//     bufferSize is negative or zero. -or- mode, access, or share contain an invalid
		//     value.
		//
		//   T:System.IO.FileNotFoundException:
		//     The file cannot be found, such as when mode is FileMode.Truncate or FileMode.Open,
		//     and the file specified by path does not exist. The file must already exist in
		//     these modes.
		//
		//   T:System.IO.IOException:
		//     An I/O error, such as specifying FileMode.CreateNew when the file specified by
		//     path already exists, occurred. -or- The stream has been closed.
		//
		//   T:System.Security.SecurityException:
		//     The caller does not have the required permission.
		//
		//   T:System.IO.DirectoryNotFoundException:
		//     The specified path is invalid, such as being on an unmapped drive.
		//
		//   T:System.UnauthorizedAccessException:
		//     The access requested is not permitted by the operating system for the specified
		//     path, such as when access is Write or ReadWrite and the file or directory is
		//     set for read-only access. -or- System.IO.FileOptions.Encrypted is specified for
		//     options, but file encryption is not supported on the current platform.
		//
		//   T:System.IO.PathTooLongException:
		//     The specified path, file name, or both exceed the system-defined maximum length.
		//     For example, on Windows-based platforms, paths must be less than 248 characters,
		//     and file names must be less than 260 characters.
		public FileStream(string path, FileMode mode, FileAccess access, FileShare share, int bufferSize,
			FileOptions options)
		{
			_impl = Enabled ? (object)new FileStreamImpl(path, mode, access, share, bufferSize, options)
				: (object)new System.IO.FileStream(path, mode, access, share, bufferSize, options);
		}

		public void Flush()
		{
			Flush(false);
		}

		public void Flush(bool flushAll)
		{
			if (Enabled)
				Our.Flush(flushAll);
			else
				Their.Flush(flushAll);
		}

		public long Seek(long offset, SeekOrigin origin)
		{
			return Enabled ? Our.Seek(offset, origin) : Their.Seek(offset, origin);
		}

		public void SetLength(long offset)
		{
			if (Enabled)
				Our.SetLength(offset);
			else
				Their.SetLength(offset);
		}

		public int Read(byte[] buffer, int offset, int count)
		{
			throw new NotImplementedException();
		}

		public void Write(byte[] buffer, int offset, int count)
		{
			if (Enabled)
				Our.Write(buffer, offset, count);
			else
				Their.Write(buffer, offset, count);
		}

		public void WriteByte(byte data)
		{
			var dataBlock = new byte[1];
			Write(dataBlock, 0, 1);
		}

		public void Dispose()
		{
			if (Enabled)
				Our.Dispose();
			else
				Their.Dispose();
		}
	}

	internal class FileStreamImpl : IDisposable /*Stream*/
	{
		private const int O_RDONLY		= 0;
		private const int O_WRONLY		= 1;
		private const int O_RDWR		= 2;

		private const int LOCK_SH		= 1;
		private const int LOCK_EX		= 2;
		private const int LOCK_NB		= 4;
		private const int LOCK_UN		= 8;

		private const int O_CREAT		= 1 * 0x40;
		private const int O_EXCL		= 2 * 0x40;
		private const int O_TRUNC		= 1 * 0x200;
		private const int O_APPEND		= 2 * 0x200; // Has different semantics from .NET? Not used.

		private const int EAGAIN = 11;

		static readonly int[] ModeFlags =
		{
			/* CreateNew */		O_CREAT | O_EXCL,
			/* Create */		O_CREAT | O_TRUNC,
			/* Open */			0,
			/* OpenOrCreate */	O_CREAT,
			/* Truncate */		O_TRUNC,
			/* Append */		0
		};

		private static readonly int[] AccessFlags = { O_RDONLY, O_WRONLY, O_RDWR };
		private static readonly int[] SeekOrigins = { 0, 1, 2 };

		private readonly string _fileName;
		private readonly bool _deleteOnClose;
		private int _fd;

		internal FileStreamImpl(string path, FileMode mode, FileAccess access, FileShare share, int bufferSize, FileOptions options)
		{
			_deleteOnClose = options.HasFlag(FileOptions.DeleteOnClose);
			_fileName = path; //_deleteOnClose ? path : null;
			_fd = -1;

			if (options.HasFlag((FileOptions.Asynchronous | FileOptions.Encrypted)))
				throw new NotImplementedException($"options: {options}");

			int flags = 0;
			if ((int)mode < 1 || (int)mode > 6)
				throw new ArgumentException($"mode: {mode}?");

			if ((int)access < 1 || (int)access > 3)
				throw new ArgumentException($"access: {access}?");

			if (mode == FileMode.Append && access != FileAccess.Write)
				throw new ArgumentException($"mode: Append, access: {access}?");

			// Arrays treated as 1-based
			flags = ModeFlags[(int)mode - 1] | AccessFlags[(int)access - 1];

			// We never modify our file on open, truncation is done separately, in order to not damage the file if it turns out to be locked
			int fd = Check("open", open(path, flags & ~O_TRUNC, Convert.ToInt32("777", 8)));

			try
			{
				LockFd(fd, share, path);
				// If the lock is taken and the file is opened, try to truncate, if necessary
				if (0 != (flags & O_TRUNC))
				{
					Check("lseek", lseek(fd, 0, 0));
					Check("ftruncate", ftruncate(fd, 0));
				}

				_fd = fd;
			}
			catch
			{
				// On error, close file/lock and rethrow
				close(fd);
				GC.SuppressFinalize(this);
				throw;
			}
		}

		private void LockFd(int fd, FileShare share, string path)
		{
			int sh = 0;
			share = share & (FileShare.ReadWrite | FileShare.Delete);
			// Better than nothing
			// But preferably should take own access method into account
			switch (share)
			{
				case FileShare.None:
					sh = LOCK_EX;
					break;

				case FileShare.Read:
				case FileShare.Write:
				case FileShare.ReadWrite:
				case FileShare.Delete:
					sh = LOCK_SH;
					break;
			}

			if (0 != sh && fd >= 0)
			{
				sh |= LOCK_NB;
				if (flock(fd, sh) < 0)
				{
					var exc = new Win32Exception();
					if (LogLevelLeast(DBG) && !path.Contains("lockfile"))
						Log($"Lock fail: {path} : {fd} : {sh} : {exc.NativeErrorCode}");

					if (EAGAIN == exc.NativeErrorCode)
						throw new IOException($"File is locked: {path}");

					throw new IOException($"Failed to take {(LOCK_EX == sh ? "Exclusive" : "Shared")} Lock for file: {path}, code: {exc.NativeErrorCode}");
				}

				if (LogLevelLeast(DBG))
					Log($"Lock ok: {path} : {fd} : {sh}");
			}
		}

		public void Flush()
		{
			Flush(true);
		}

		public void Flush(bool toDisk)
		{
			if (_fd <= 0)
				throw new ObjectDisposedException("FileStream");

			Check("fsync", fsync(_fd));
		}


		private void ThrowLastError(String name)
		{
			String msg = new Win32Exception().Message;
			Log($"{name}() throw: {msg}");
			throw new IOException($"{name}() returned error: {msg}");
		}

		private int Check(String name, int retVal)
		{
			if (retVal < 0)
				ThrowLastError(name);

			return retVal;
		}

		public int Read(byte[] buffer, int offset, int count)
		{
			throw new NotImplementedException();
		}


		public long Seek(long offset, SeekOrigin origin)
		{
			if (_fd <= 0)
				throw new ObjectDisposedException("FileStream");

			if ((uint)origin > 2)
				throw new ArgumentException("origin");

			return Check("lseek", lseek(_fd, (int)offset, SeekOrigins[(int)origin]));
		}


		public void SetLength(long offset)
		{
			if (_fd <= 0)
				throw new ObjectDisposedException("FileStream");

			Check("ftruncate", ftruncate(_fd, (int)offset));
		}


		public void Write(byte[] buffer, int offset, int count)
		{
			if (null == buffer)
				throw new ArgumentException();

			int length = buffer.Length;
			if ((offset | count) < 0 || offset + count < 0 || offset + count > length)
				throw new ArgumentException();

			if (_fd <= 0)
				throw new ObjectDisposedException("FileStream");

			long written = -1;
			unsafe
			{
				fixed (byte* ptr = buffer)
				{
					written = write(_fd, new IntPtr(ptr + (uint)offset), (uint)count);
				}
			}

			if (written != count)
				ThrowLastError("write");
		}

		~FileStreamImpl()
		{
			ReleaseUnmanagedResources();
		}

		public void Dispose()
		{
			ReleaseUnmanagedResources();
			GC.SuppressFinalize(this);
		}

		private void ReleaseUnmanagedResources()
		{
			//if (_fd > 0 && !disposing)
			//	Log($"Dispose({disposing} {_fileName} {_fd} del: {_deleteOnClose} )");

			if (_fd > 0)
				close(_fd);

			_fd = -1;
			if (_deleteOnClose)
				unlink(_fileName);
		}


		[DllImport("libc", SetLastError = true)]
		private static extern int open(String pathname, int flags, int mode);

		[DllImport("libc")]
		private static extern int close(int fd);

		[DllImport("libc", SetLastError = true)]
		private static extern int fsync(int fd);

		[DllImport("libc", SetLastError = true)]
		private static extern int lseek(int fd, int offset, int whence);

		[DllImport("libc", SetLastError = true)]
		private static extern int ftruncate(int fd, int offset);

		[DllImport("libc", SetLastError = true)]
		private static extern long write(int fd, IntPtr buf, ulong count);

		[DllImport("libc", SetLastError = true)]
		private static extern int flock(int fd, int operation);

		[DllImport("libc", SetLastError = true)]
		private static extern int unlink(String pathName);
	}
}

﻿// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
// 
//   http://www.apache.org/licenses/LICENSE-2.0
// 
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

using System;
using System.IO;
using System.Linq;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Org.Apache.REEF.IO.FileSystem;
using Org.Apache.REEF.IO.FileSystem.Hadoop;
using Org.Apache.REEF.IO.FileSystem.Hadoop.Parameters;
using Org.Apache.REEF.Tang.Annotations;
using Org.Apache.REEF.Tang.Implementations.Tang;

namespace Org.Apache.REEF.IO.Tests
{
    /// <summary>
    /// Tests for HadoopFileSystem.
    /// </summary>
    /// <see cref="HadoopFileSystem" />    
    [TestClass]
    [Ignore] // These tests need to be run in an environment with HDFS installed.
    public sealed class TestHadoopFileSystem
    {
        private HadoopFileSystem _fileSystem;

        private Uri GetTempUri()
        {
            return
                new Uri(_fileSystem.UriPrefix + "/tmp/TestHadoopFileSystem-" +
                        DateTime.Now.ToString("yyyyMMddHHmmssfff"));
        }

        /// <summary>
        /// Sets up the file system instance to be used for the tests.
        /// </summary>
        [TestInitialize]
        public void SetupFileSystem()
        {
            _fileSystem =
                TangFactory.GetTang()
                    .NewInjector(HadoopFileSystemConfiguration.ConfigurationModule.Build())
                    .GetInstance<HadoopFileSystem>();
        }

        /// <summary>
        /// Creates a temp file locally, uploads it to HDFS and downloads it again.
        /// </summary>
        [TestMethod]
        public void TestCopyFromLocalAndBack()
        {
            var localFile = FileSystemTestUtilities.MakeLocalTempFile();
            var localFileDownloaded = localFile + ".2";
            var remoteUri = GetTempUri();

            _fileSystem.CopyFromLocal(localFile, remoteUri);
            _fileSystem.CopyToLocal(remoteUri, localFileDownloaded);

            Assert.IsTrue(message: "A file up and downloaded should exist on the local file system.",
                condition: File.Exists(localFileDownloaded));
            Assert.IsTrue(message: "A file up and downloaded should not have changed content.",
                condition: FileSystemTestUtilities.HaveSameContent(localFile, localFileDownloaded));

            _fileSystem.Delete(remoteUri);
            File.Delete(localFile);
            File.Delete(localFileDownloaded);
        }

        /// <summary>
        /// Tests whether .Exists() works.
        /// </summary>
        [TestMethod]
        public void TestExists()
        {
            var remoteUri = GetTempUri();
            Assert.IsFalse(message: "The file should not exist yet", condition: _fileSystem.Exists(remoteUri));
            var localFile = FileSystemTestUtilities.MakeLocalTempFile();
            _fileSystem.CopyFromLocal(localFile, remoteUri);
            Assert.IsTrue(message: "The file should now exist", condition: _fileSystem.Exists(remoteUri));
            _fileSystem.Delete(remoteUri);
            Assert.IsFalse(message: "The file should no longer exist", condition: _fileSystem.Exists(remoteUri));
            File.Delete(localFile);
        }

        /// <summary>
        /// Tests for .GetChildren().
        /// </summary>
        [TestMethod]
        public void TestGetChildren()
        {
            // Make a directory
            var remoteDirectory = GetTempUri();
            _fileSystem.CreateDirectory(remoteDirectory);
            // Check that it is empty
            Assert.AreEqual(message: "The directory should be empty.", expected: 0,
                actual: _fileSystem.GetChildren(remoteDirectory).Count());
            // Upload some localfile there
            var localTempFile = FileSystemTestUtilities.MakeLocalTempFile();
            var remoteUri = new Uri(remoteDirectory, Path.GetFileName(localTempFile));
            _fileSystem.CopyFromLocal(localTempFile, remoteUri);
            // Check that it is on the listing
            var uriInResult = _fileSystem.GetChildren(remoteUri).First();
            Assert.AreEqual(remoteUri, uriInResult);

            // Download the file and make sure it is the same as before
            var downloadedFileName = localTempFile + ".downloaded";
            _fileSystem.CopyToLocal(uriInResult, downloadedFileName);
            FileSystemTestUtilities.HaveSameContent(localTempFile, downloadedFileName);
            File.Delete(localTempFile);
            File.Delete(downloadedFileName);

            // Delete the file
            _fileSystem.Delete(remoteUri);
            // Check that the folder is empty again
            Assert.AreEqual(message: "The directory should be empty.", expected: 0,
                actual: _fileSystem.GetChildren(remoteDirectory).Count());
            // Delete the folder
            _fileSystem.DeleteDirectory(remoteDirectory);
        }

        [TestMethod]
        [ExpectedException(typeof(NotImplementedException),
            "Open() is not supported by HadoopFileSystem. Use CopyToLocal and open the local file instead.")]
        public void TestOpen()
        {
            _fileSystem.Open(GetTempUri());
        }

        [TestMethod]
        [ExpectedException(typeof(NotImplementedException),
            "Create() is not supported by HadoopFileSystem. Create a local file and use CopyFromLocal instead.")]
        public void TestCreate()
        {
            _fileSystem.Create(GetTempUri());
        }

        /// <summary>
        /// This test is to make sure with the HadoopFileSystemConfiguration, HadoopFileSystem can be injected.
        /// </summary>
        [TestMethod]
        public void TestHadoopFileSystemConfiguration()
        {
            var fileSystemTest = TangFactory.GetTang().NewInjector(HadoopFileSystemConfiguration.ConfigurationModule
                .Build())
                .GetInstance<FileSystemTest>();
            Assert.IsTrue(fileSystemTest.FileSystem is HadoopFileSystem);
        }
    }

    class FileSystemTest
    {
        public IFileSystem FileSystem { get; private set; }

        [Inject]
        private FileSystemTest(IFileSystem fileSystem)
        {
            FileSystem = fileSystem;
        }
    }
}
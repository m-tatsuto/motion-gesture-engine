import Foundation

public enum MotionTraceWriteDisposition: Equatable, Sendable {
  case written
  case backpressured
}

/// Injected byte destination used by the recorder for deterministic tests and local files.
///
/// Implementations must not expose a destination as committed before `commit()` succeeds.
public protocol MotionTraceOutput: AnyObject {
  var temporaryURL: URL? { get }
  var destinationURL: URL? { get }

  func start() throws
  func write(_ data: Data) throws -> MotionTraceWriteDisposition
  func commit() throws -> URL?
  func abortPreservingPartial()
}

/// Streams an uncompressed trace to a same-directory temporary file and atomically renames it.
public final class AtomicFileMotionTraceOutput: MotionTraceOutput {
  public let destinationURL: URL?
  public private(set) var temporaryURL: URL?

  private let fileManager: FileManager
  private var handle: FileHandle?
  private var committed = false

  public init(destinationURL: URL, fileManager: FileManager = .default) {
    self.destinationURL = destinationURL
    self.fileManager = fileManager
  }

  public func start() throws {
    guard handle == nil, !committed, let destinationURL else {
      throw AtomicFileOutputError.invalidState
    }
    guard destinationURL.pathExtension == "jsonl",
      destinationURL.lastPathComponent.hasSuffix(".mge.jsonl")
    else {
      throw AtomicFileOutputError.invalidSuffix
    }
    guard !fileManager.fileExists(atPath: destinationURL.path) else {
      throw AtomicFileOutputError.destinationExists
    }

    let parent = destinationURL.deletingLastPathComponent()
    var isDirectory: ObjCBool = false
    guard fileManager.fileExists(atPath: parent.path, isDirectory: &isDirectory),
      isDirectory.boolValue
    else {
      throw AtomicFileOutputError.parentDirectoryMissing
    }

    let temporaryURL = parent.appendingPathComponent(
      ".\(destinationURL.lastPathComponent).\(UUID().uuidString.lowercased()).partial"
    )
    guard fileManager.createFile(atPath: temporaryURL.path, contents: nil) else {
      throw AtomicFileOutputError.cannotCreateTemporaryFile
    }
    self.temporaryURL = temporaryURL
    handle = try FileHandle(forWritingTo: temporaryURL)
  }

  public func write(_ data: Data) throws -> MotionTraceWriteDisposition {
    guard let handle, !committed else { throw AtomicFileOutputError.invalidState }
    try handle.write(contentsOf: data)
    return .written
  }

  public func commit() throws -> URL? {
    guard let handle, let temporaryURL, let destinationURL, !committed else {
      throw AtomicFileOutputError.invalidState
    }
    try handle.synchronize()
    try handle.close()
    self.handle = nil
    try fileManager.moveItem(at: temporaryURL, to: destinationURL)
    committed = true
    return destinationURL
  }

  public func abortPreservingPartial() {
    try? handle?.synchronize()
    try? handle?.close()
    handle = nil
  }
}

enum AtomicFileOutputError: Error {
  case invalidState
  case invalidSuffix
  case destinationExists
  case parentDirectoryMissing
  case cannotCreateTemporaryFile
}

import Foundation

enum LanTransferProtocol {
  static let magic = Data([0x4C, 0x4E, 0x54, 0x32]) // "LNT2"

  static let typeHello: UInt8 = 0x01
  static let typeData: UInt8  = 0x02

  static let encodingUtf8: UInt8 = 0x01
  static let encodingBinary: UInt8 = 0x02

  static let flagEncrypted: UInt8 = 0x01

  struct DataHeaderV2 {
    let encoding: UInt8
    let flags: UInt8
    let totalPlainBytes: Int64
    let chunkSize: Int32
    let salt16: Data
    let baseNonce8: Data
    var encrypted: Bool { (flags & flagEncrypted) != 0 }
  }

  static func helloFrame(sessionKey: String?) -> Data {
    let key = (sessionKey ?? "")
    let keyData = key.data(using: .utf8) ?? Data()
    var frame = Data()
    frame.append(magic)
    frame.append(typeHello)
    frame.appendUInt16(UInt16(min(keyData.count, 65535)))
    frame.append(keyData.prefix(65535))
    return frame
  }

  static func parseHello(prefix7: Data, keyData: Data) throws -> String {
    guard prefix7.count == 7 else { throw NSError(domain: "LanTransfer", code: 1) }
    guard prefix7.prefix(4) == magic, prefix7[4] == typeHello else { throw NSError(domain: "LanTransfer", code: 2) }
    let len = Int(prefix7.readUInt16(at: 5))
    guard keyData.count == len else { throw NSError(domain: "LanTransfer", code: 3) }
    return String(data: keyData, encoding: .utf8) ?? ""
  }

  static func dataHeaderFrameV2(
    encoding: UInt8,
    encrypted: Bool,
    totalPlainBytes: Int64,
    chunkSize: Int32,
    salt16: Data,
    baseNonce8: Data
  ) -> Data {
    var frame = Data()
    frame.append(magic)
    frame.append(typeData)
    frame.append(encoding)
    frame.append(encrypted ? flagEncrypted : 0)
    frame.appendInt64(totalPlainBytes)
    frame.appendInt32(chunkSize)
    frame.append(salt16)     // 16
    frame.append(baseNonce8) // 8
    return frame
  }

  static func parseDataHeaderV2(_ data: Data) throws -> DataHeaderV2 {
    // 4 +1 +1 +1 +8 +4 +16 +8 = 43 bytes
    guard data.count == 43 else { throw NSError(domain: "LanTransfer", code: 10) }
    guard data.prefix(4) == magic, data[4] == typeData else { throw NSError(domain: "LanTransfer", code: 11) }

    let encoding = data[5]
    let flags = data[6]
    let total = data.readInt64(at: 7)
    let chunkSize = data.readInt32(at: 15)

    let salt16 = data.subdata(in: 19..<35)
    let baseNonce8 = data.subdata(in: 35..<43)

    if total < 0 { throw NSError(domain: "LanTransfer", code: 12) }
    if chunkSize < 1024 || chunkSize > 1024*1024 { throw NSError(domain: "LanTransfer", code: 13) }

    return DataHeaderV2(
      encoding: encoding,
      flags: flags,
      totalPlainBytes: total,
      chunkSize: chunkSize,
      salt16: salt16,
      baseNonce8: baseNonce8
    )
  }
}

// MARK: - Data helpers

private extension Data {
  mutating func appendUInt16(_ value: UInt16) {
    var v = value.bigEndian
    withUnsafeBytes(of: &v) { append(contentsOf: $0) }
  }

  mutating func appendInt32(_ value: Int32) {
    var v = value.bigEndian
    withUnsafeBytes(of: &v) { append(contentsOf: $0) }
  }

  mutating func appendInt64(_ value: Int64) {
    var v = value.bigEndian
    withUnsafeBytes(of: &v) { append(contentsOf: $0) }
  }

  func readUInt16(at offset: Int) -> UInt16 {
    let sub = self.subdata(in: offset..<(offset+2))
    return sub.withUnsafeBytes { $0.load(as: UInt16.self).bigEndian }
  }

  func readInt32(at offset: Int) -> Int32 {
    let sub = self.subdata(in: offset..<(offset+4))
    return sub.withUnsafeBytes { $0.load(as: Int32.self).bigEndian }
  }

  func readInt64(at offset: Int) -> Int64 {
    let sub = self.subdata(in: offset..<(offset+8))
    return sub.withUnsafeBytes { $0.load(as: Int64.self).bigEndian }
  }
}
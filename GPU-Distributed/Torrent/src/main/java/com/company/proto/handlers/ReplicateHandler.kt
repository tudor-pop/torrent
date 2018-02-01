package com.company.proto.handlers

import com.company.proto.*
import com.company.proto.torrent.Torrent
import com.google.protobuf.ByteString
import java.io.IOException
import java.net.ConnectException
import java.net.Socket

class ReplicateHandler(
		private val storage: MutableMap<Torrent.FileInfo, ByteString>,
		private val duplicates: MutableList<Duplicate>,
		private val currentNode: Torrent.Node
) : Handler {
	
	override fun handle(message: Torrent.Message): Torrent.Message {
		val filename = message.replicateRequest.fileInfo.filename
		val hash = message.replicateRequest.fileInfo.hash
		if (filename.isNullOrBlank()) return messageError()
		
		val fileInfoDup = storage.keys.find { it.hash == hash } ?: return replicate(message.replicateRequest.fileInfo)
		
		duplicates.add(Duplicate(fileInfoDup, storage[fileInfoDup]))
		
		return successFileReplicated(message.chunkRequest)
	}
	
	private fun messageError(): Torrent.Message {
		val build = Torrent.ReplicateResponse.newBuilder()
				.setStatus(Torrent.Status.MESSAGE_ERROR)
				.setErrorMessage("The filename is empty")
				.build()
		return Torrent.Message.newBuilder()
				.setType(Torrent.Message.Type.REPLICATE_RESPONSE)
				.setReplicateResponse(build)
				.build()
	}
	
	private fun replicate(fileinfo: Torrent.FileInfo): Torrent.Message {
		println("Replicate: ${fileinfo.hash.hashToReadableMD5()}")
		val replicateResponse = Torrent.ReplicateResponse.newBuilder()
		val chunkData = mutableMapOf<Torrent.ChunkInfo, ByteString>()
		fileinfo.chunksList.forEach { chunkInfo ->
			nodeObservable(currentNode).forEach { node ->
				Socket(node.host, node.port).use { socket ->
					try {
						val output = socket.getDataOutputStream()
						val input = socket.getDataInputStream()
						
						val reqMessage = createChunkRequest(fileinfo.hash, chunkInfo.index)
						output.writeMessage(reqMessage)
						
						val resMessage = input.readMessage()
						val chunkResponse = resMessage.chunkResponse
						val chunkInfoResponse = Torrent.ChunkInfo.newBuilder()
								.setHash(chunkResponse.data.toMD5Hash())
								.setIndex(chunkInfo.index)
								.setSize(chunkResponse.data.size())
								.build()
						chunkData[chunkInfoResponse] = chunkResponse.data
						val replicationStatus = Torrent.NodeReplicationStatus.newBuilder()
								.setNode(node)
								.setChunkIndex(chunkInfo.index)
								.setStatus(chunkResponse.status)
						replicateResponse.addNodeStatusList(replicationStatus.build())
					} catch (error: IOException) {
						val replicationStatus = Torrent.NodeReplicationStatus.newBuilder()
								.setNode(node)
								.setChunkIndex(chunkInfo.index)
						if (error is ConnectException) {
							replicateResponse.errorMessage = "NETWORK_ERROR"
							replicationStatus.status = Torrent.Status.NETWORK_ERROR
						} else {
							replicateResponse.errorMessage = "PROCESSING_ERROR"
							replicationStatus.status = Torrent.Status.PROCESSING_ERROR
						}
						replicateResponse.addNodeStatusList(replicationStatus.build())
					}
				}
			}
		}
		val response = Torrent.Message.newBuilder().setType(Torrent.Message.Type.REPLICATE_RESPONSE)
		val finalData = ByteString.copyFrom(chunkData.values)
		if (finalData.toMD5Hash() == fileinfo.hash) {
			storage[fileinfo] = finalData
			response.replicateResponse = replicateResponse.build()
		} else {
			response.replicateResponse = Torrent.ReplicateResponse.newBuilder()
					.setStatus(Torrent.Status.PROCESSING_ERROR)
					.setErrorMessage("The received chunks do not add up to file size")
					.build()
		}
		return response.build()
	}
	
	private fun createChunkRequest(fileHash: ByteString, chunkIndex: Int): Torrent.Message {
		val build = Torrent.ChunkRequest.newBuilder()
				.setFileHash(fileHash)
				.setChunkIndex(chunkIndex)
				.build()
		return Torrent.Message.newBuilder()
				.setType(Torrent.Message.Type.CHUNK_REQUEST)
				.setChunkRequest(build)
				.build()
	}
	
	private fun successFileReplicated(chunkRequest: Torrent.ChunkRequest): Torrent.Message {
		println("File replicated for ${chunkRequest.fileHash.hashToReadableMD5()}")
		val nodeReplicationStatus = Torrent.NodeReplicationStatus.newBuilder()
				.setStatus(Torrent.Status.SUCCESS)
				.setNode(currentNode)
				.setChunkIndex(chunkRequest.chunkIndex)
				.build()
		val build = Torrent.ReplicateResponse.newBuilder()
				.addNodeStatusList(nodeReplicationStatus)
				.setStatus(Torrent.Status.SUCCESS)
				.build()
		return Torrent.Message.newBuilder()
				.setType(Torrent.Message.Type.REPLICATE_RESPONSE)
				.setReplicateResponse(build)
				.build()
	}
}

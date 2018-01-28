package com.company.proto.handlers

import com.company.proto.*
import com.company.proto.torrent.Torrent
import com.google.protobuf.ByteString
import io.reactivex.schedulers.Timed

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.Socket
import java.util.concurrent.TimeUnit

import com.company.proto.UtilsIO.readMessageFrom
import com.company.proto.UtilsIO.writeMessageTo

class SearchHandler(storage: Map<Torrent.FileInfo, ByteString>, private val currentNode: Torrent.Node) : Handler {
	private val localSearchHandler: LocalSearchHandler = LocalSearchHandler(storage)
	
	override fun handle(message: Torrent.Message): Torrent.Message {
		val regex = message.searchRequest.regex
		
		if (!regex.isValidRegex()) return messageError(regex)
		
		val build = Torrent.LocalSearchRequest.newBuilder()
				.setRegex(regex)
				.build()
		
		val localSearchRequest = Torrent.Message.newBuilder()
				.setType(Torrent.Message.Type.LOCAL_SEARCH_REQUEST)
				.setLocalSearchRequest(build)
				.build()
		
		val localResponse = localSearchHandler.handle(localSearchRequest)
		val localSearchResponse = localResponse.localSearchResponse
		val nodeSearchResult = Torrent.NodeSearchResult.newBuilder()
				.setNode(currentNode)
				.setStatus(localSearchResponse.status)
				.setErrorMessage(localSearchResponse.errorMessage)
				.addAllFiles(localSearchResponse.fileInfoList)
				.build()
		
		return askOtherNodesFor(nodeSearchResult, regex)
	}
	
	private fun askOtherNodesFor(currentNodeSearchResult: Torrent.NodeSearchResult, regex: String): Torrent.Message {
		val searchResponse = Torrent.SearchResponse.newBuilder()
				.setStatus(Torrent.Status.SUCCESS)
				.setErrorMessage("SUCCESS")
		searchResponse.addResults(currentNodeSearchResult)
		nodeObservable(currentNode)
				//				.timeInterval(TimeUnit.MILLISECONDS)
				//				.map(Timed::value)
				.subscribe({ node ->
					try {
						Socket(node.getHost(), node.getPort()).use({ socket ->
							val output = DataOutputStream(socket.getOutputStream())
							val input = DataInputStream(socket.getInputStream())
							
							val reqMessage = createLocalSearchRequest(regex)
							writeMessageTo(reqMessage, output)
							
							val resMessage = readMessageFrom(input)
							val localSearchResponse = resMessage.localSearchResponse
							
							val nodeSearchResult = Torrent.NodeSearchResult.newBuilder()
									.setNode(node)
									.setStatus(localSearchResponse.status)
									.setErrorMessage(localSearchResponse.errorMessage)
									.addAllFiles(localSearchResponse.fileInfoList)
									.build()
							searchResponse.addResults(nodeSearchResult)
						})
					} catch (error: IOException) {
						val fileInfo = Torrent.FileInfo.newBuilder().setFilename(regex).build()
						val nodeSearchResult = Torrent.NodeSearchResult.newBuilder()
								.addFiles(fileInfo)
								.setNode(node)
						if (error is ConnectException) {
							nodeSearchResult.setStatus(Torrent.Status.NETWORK_ERROR)
							nodeSearchResult.setErrorMessage("NETWORK_ERROR")
						} else {
							nodeSearchResult.setStatus(Torrent.Status.PROCESSING_ERROR)
							nodeSearchResult.setErrorMessage("PROCESSING_ERROR")
						}
						searchResponse.addResults(nodeSearchResult.build())
						System.out.println("node: " + node.toString() + "\n" + error.toString())
					}
				}, Throwable::printStackTrace)
		
		return Torrent.Message.newBuilder()
				.setType(Torrent.Message.Type.SEARCH_RESPONSE)
				.setSearchResponse(searchResponse.build())
				.build()
	}
	
	private fun createLocalSearchRequest(regex: String): Torrent.Message {
		val localSearchRequest = Torrent.LocalSearchRequest.newBuilder()
				.setRegex(regex)
				.build()
		return Torrent.Message.newBuilder()
				.setLocalSearchRequest(localSearchRequest)
				.setType(Torrent.Message.Type.LOCAL_SEARCH_REQUEST)
				.build()
	}
	
	private fun messageError(filename: String): Torrent.Message {
		val fileInfo = Torrent.FileInfo.newBuilder()
				.setFilename(filename)
				.build()
		val nodeSearchResult = Torrent.NodeSearchResult
				.newBuilder()
				.setNode(currentNode)
				.setStatus(Torrent.Status.MESSAGE_ERROR)
				.setErrorMessage("Regex $filename is invalid")
				.addFiles(fileInfo)
				.build()
		val searchResponse = Torrent.SearchResponse
				.newBuilder()
				.setStatus(Torrent.Status.MESSAGE_ERROR)
				.setErrorMessage("MESSAGE_ERROR")
				.addResults(nodeSearchResult)
				.build()
		return Torrent.Message.newBuilder()
				.setType(Torrent.Message.Type.SEARCH_RESPONSE)
				.setSearchResponse(searchResponse)
				.build()
	}
}

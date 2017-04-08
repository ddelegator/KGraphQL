package com.github.pgutkowski.kql.schema.impl

import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.pgutkowski.kql.SyntaxException
import com.github.pgutkowski.kql.request.Arguments
import com.github.pgutkowski.kql.request.GraphNode
import com.github.pgutkowski.kql.request.Request
import com.github.pgutkowski.kql.request.RequestParser
import com.github.pgutkowski.kql.result.Result
import com.github.pgutkowski.kql.result.ResultSerializer
import com.github.pgutkowski.kql.schema.Schema
import kotlin.reflect.full.starProjectedType

class DefaultSchema(
        queries: ArrayList<KQLObject.Query<*>>,
        mutations: ArrayList<KQLObject.Mutation<*>>,
        simpleTypes : ArrayList<KQLObject.Simple<*>>,
        inputs: ArrayList<KQLObject.Input<*>>,
        scalars: ArrayList<KQLObject.Scalar<*>>
) : Schema, DefaultSchemaStructure(
        queries, mutations, simpleTypes, inputs, scalars
) {
    private val requestParser = RequestParser { resolveActionType(it) }

    val objectMapper = jacksonObjectMapper()

    init {
        objectMapper.registerModule(
                SimpleModule("KQL result serializer").addSerializer(Result::class.java, ResultSerializer(this))
        )
    }

    //TODO: fix error handling on stage of serializing
    override fun handleRequest(request: String): String {
        try {
            return objectMapper.writeValueAsString(createResult(request))
        } catch(e: Exception) {
            return "{\"errors\" : { \"message\": \"Caught ${e.javaClass.canonicalName}: ${e.message}\"}}"
        }
    }

    /**
     * this method is only fetching data
     */
    fun createResult(request: String): Result {
        val parsedRequest = requestParser.parse(request)
        val data: MutableMap<String, Any?> = mutableMapOf()
        when (parsedRequest.action) {
            Request.Action.QUERY -> {
                for (query in parsedRequest.graph.map { it.key }) {
                    val queryFunction = findQueryFunction(query, Arguments())
                    data.put(query, queryFunction.invoke())
                }
            }
            Request.Action.MUTATION -> {
                for (mutation in parsedRequest.graph) {
                    val args = extractArguments(mutation)
                    val mutationFunction = findMutationFunction(mutation.key, args)
                    data.put(mutation.key, invokeWithArgs(mutationFunction, args))
                }
            }
            else -> throw IllegalArgumentException("Not supported action: ${parsedRequest.action}")
        }
        return Result(parsedRequest, data, null)
    }

    fun <T> invokeWithArgs(functionWrapper: FunctionWrapper<T>, args: Arguments): Any? {
        val transformedArgs : MutableList<Any?> = mutableListOf()
        functionWrapper.kFunction.parameters.forEach { parameter ->
            val value = args[parameter.name!!]
            if(value == null){
                if(!parameter.isOptional){
                    throw IllegalArgumentException("${functionWrapper.kFunction.name} argument ${parameter.name} is not optional, value cannot be null")
                } else {
                    transformedArgs.add(null)
                }
            } else {
                val transformedValue : Any = when(parameter.type){
                    Int::class.starProjectedType ->{
                        try {
                            value.toInt()
                        } catch(e : Exception){
                            throw SyntaxException("argument \'${value.dropQuotes()}\' is not value of type: ${Int::class}")
                        }
                    }
                    else -> value.dropQuotes()
                }

                transformedArgs.add(transformedValue)
            }

        }

        return functionWrapper.invoke(*transformedArgs.toTypedArray())
    }

    fun extractArguments(graphNode: GraphNode): Arguments {
        if(graphNode is GraphNode.ToArguments){
            return graphNode.arguments
        } else {
            return Arguments()
        }
    }

    fun resolveActionType(token: String): Request.Action {
        if (queries.values.any { it.name.equals(token, true) }) return Request.Action.QUERY
        if (mutations.values.any { it.name.equals(token, true) }) return Request.Action.MUTATION
        throw IllegalArgumentException("Cannot infer request type for name $token")
    }

    fun String.dropQuotes() : String = if(startsWith('\"') && endsWith('\"')) drop(1).dropLast(1) else this
}
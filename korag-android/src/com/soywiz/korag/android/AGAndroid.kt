package com.soywiz.korag.android

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.jtransc.FastMemory
import com.soywiz.korag.AG
import com.soywiz.korag.AGFactory
import com.soywiz.korag.geom.Matrix4
import com.soywiz.korag.shader.Program
import com.soywiz.korag.shader.Uniform
import com.soywiz.korag.shader.VarType
import com.soywiz.korag.shader.VertexLayout
import com.soywiz.korag.shader.gl.toGlSlString
import com.soywiz.korim.bitmap.Bitmap32
import com.soywiz.korim.bitmap.Bitmap8
import com.soywiz.korim.color.RGBA
import com.soywiz.korio.android.KorioAndroidContext
import com.soywiz.korio.error.invalidOp
import com.soywiz.korio.util.Once
import java.io.Closeable
import java.nio.ByteBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class AGFactoryAndroid : AGFactory() {
	override val available: Boolean = true // @TODO: Detect android
	override val priority: Int = 1500
	override fun create(): AG = AGAndroid()
}

private typealias GL = GLES20
private typealias gl = GLES20

class AGAndroid : AG() {
	val ag = this
	val glv = GLSurfaceView(KorioAndroidContext)
	override val nativeComponent: Any = glv

	init {
		//glv.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
		glv.setEGLContextClientVersion(2)
		glv.setRenderer(object : GLSurfaceView.Renderer {
			val onReadyOnce = Once()

			private fun initializeOnce() {
				onReadyOnce {
					ready()
				}
			}

			override fun onDrawFrame(gl1: GL10) {
				//println("Android.onDrawFrame")
				initializeOnce()
				//if (DEBUG_AGANDROID) println("Android.onDrawFrame... " + Thread.currentThread())
				onRender(ag)
				//gl = gl1 as GLES20
			}

			override fun onSurfaceChanged(gl1: GL10, width: Int, height: Int) {
				backWidth = width
				backHeight = height

				//gl = gl1 as GLES20
				gl.glViewport(0, 0, backWidth, backHeight)

				initializeOnce()
				//resized()
				onRender(ag)
			}

			override fun onSurfaceCreated(gl1: GL10, p1: EGLConfig) {
				initializeOnce()
				//gl = gl1 as GLES20
				onRender(ag)
			}
		})
		glv.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
	}

	override fun repaint() {
		glv.requestRender()
	}
//
	//override fun resized() {
	//	if (initialized) {
	//		gl.glViewport(0, 0, backWidth, backHeight)
	//	}
	//}

	override fun clear(color: Int, depth: Float, stencil: Int, clearColor: Boolean, clearDepth: Boolean, clearStencil: Boolean) {
		var bits = 0
		if (clearColor) bits = bits or GL.GL_COLOR_BUFFER_BIT
		if (clearDepth) bits = bits or GL.GL_DEPTH_BUFFER_BIT
		if (clearStencil) bits = bits or GL.GL_STENCIL_BUFFER_BIT
		gl.glClearColor(RGBA.getRf(color), RGBA.getGf(color), RGBA.getBf(color), RGBA.getAf(color))
		gl.glClearDepthf(depth)
		gl.glClearStencil(stencil)
		gl.glClear(bits)
		//println("Android.glClear")
	}

	override fun createBuffer(kind: Buffer.Kind): Buffer = AndroidBuffer(kind)

	private fun BlendFactor.toGl() = when (this) {
		BlendFactor.DESTINATION_ALPHA -> GL.GL_DST_ALPHA
		BlendFactor.DESTINATION_COLOR -> GL.GL_DST_COLOR
		BlendFactor.ONE -> GL.GL_ONE
		BlendFactor.ONE_MINUS_DESTINATION_ALPHA -> GL.GL_ONE_MINUS_DST_ALPHA
		BlendFactor.ONE_MINUS_DESTINATION_COLOR -> GL.GL_ONE_MINUS_DST_COLOR
		BlendFactor.ONE_MINUS_SOURCE_ALPHA -> GL.GL_ONE_MINUS_SRC_ALPHA
		BlendFactor.ONE_MINUS_SOURCE_COLOR -> GL.GL_ONE_MINUS_SRC_COLOR
		BlendFactor.SOURCE_ALPHA -> GL.GL_SRC_ALPHA
		BlendFactor.SOURCE_COLOR -> GL.GL_SRC_COLOR
		BlendFactor.ZERO -> GL.GL_ZERO
	}

	override fun draw(vertices: Buffer, indices: Buffer, program: Program, type: DrawType, vertexLayout: VertexLayout, vertexCount: Int, offset: Int, blending: BlendFactors, uniforms: Map<Uniform, Any>) {
		checkBuffers(vertices, indices)
		val glProgram = getProgram(program)
		(vertices as AndroidBuffer).bind()
		(indices as AndroidBuffer).bind()
		glProgram.use()

		for (n in vertexLayout.attributePositions.indices) {
			val att = vertexLayout.attributes[n]
			val off = vertexLayout.attributePositions[n]
			val loc = gl.glGetAttribLocation(glProgram.id, att.name).toInt()
			val glElementType = att.type.glElementType
			val elementCount = att.type.elementCount
			val totalSize = vertexLayout.totalSize
			if (loc >= 0) {
				gl.glEnableVertexAttribArray(loc)
				gl.glVertexAttribPointer(loc, elementCount, glElementType, att.normalized, totalSize, off)
			}
		}
		var textureUnit = 0
		for ((uniform, value) in uniforms) {
			val location = gl.glGetUniformLocation(glProgram.id, uniform.name)
			when (uniform.type) {
				VarType.TextureUnit -> {
					val unit = value as TextureUnit
					gl.glActiveTexture(GL.GL_TEXTURE0 + textureUnit)
					val tex = (unit.texture as AndroidTexture?)
					tex?.bind()
					tex?.setFilter(unit.linear)
					gl.glUniform1i(location, textureUnit)
					textureUnit++
				}
				VarType.Mat4 -> {
					gl.glUniformMatrix4fv(location, 1, false, (value as Matrix4).data, 0)
				}
				VarType.Float1 -> {
					gl.glUniform1f(location, (value as Number).toFloat())
				}
				else -> invalidOp("Don't know how to set uniform ${uniform.type}")
			}
		}

		if (blending.disabled) {
			gl.glDisable(GL.GL_BLEND)
		} else {
			gl.glEnable(GL.GL_BLEND)
			gl.glBlendFuncSeparate(blending.srcRGB.toGl(), blending.dstRGB.toGl(), blending.srcA.toGl(), blending.dstA.toGl())
		}

		gl.glDrawElements(type.glDrawMode, vertexCount, GL.GL_UNSIGNED_SHORT, offset)

		gl.glActiveTexture(GL.GL_TEXTURE0)
		for (att in vertexLayout.attributes) {
			val loc = gl.glGetAttribLocation(glProgram.id, att.name).toInt()
			if (loc >= 0) {
				gl.glDisableVertexAttribArray(loc)
			}
		}
	}

	val DrawType.glDrawMode: Int get() = when (this) {
		DrawType.TRIANGLES -> GL.GL_TRIANGLES
		DrawType.TRIANGLE_STRIP -> GL.GL_TRIANGLE_STRIP
	}

	val VarType.glElementType: Int get() = when (this) {
		VarType.Int1 -> GL.GL_INT
		VarType.Float1, VarType.Float2, VarType.Float3, VarType.Float4 -> GL.GL_FLOAT
		VarType.Mat4 -> GL.GL_FLOAT
		VarType.Bool1 -> GL.GL_UNSIGNED_BYTE
		VarType.Byte4 -> GL.GL_UNSIGNED_BYTE
		VarType.TextureUnit -> GL.GL_INT
	}

	private val programs = hashMapOf<String, AndroidProgram>()
	fun getProgram(program: Program): AndroidProgram = programs.getOrPut(program.name) { AndroidProgram(program) }

	class AndroidProgram(val program: Program) : Closeable {
		val id = gl.glCreateProgram()
		val fragmentShaderId = createShader(GL.GL_FRAGMENT_SHADER, program.fragment.toGlSlString())
		val vertexShaderId = createShader(GL.GL_VERTEX_SHADER, program.vertex.toGlSlString())

		init {
			gl.glAttachShader(id, fragmentShaderId)
			gl.glAttachShader(id, vertexShaderId)
			gl.glLinkProgram(id)
			val out = IntArray(1)
			gl.glGetProgramiv(id, GL.GL_LINK_STATUS, out, 0)
			if (out[0] != GL.GL_TRUE) {
				val msg = gl.glGetProgramInfoLog(id)
				throw RuntimeException("Error Linking Program : '$msg' programId=$id")
			}
		}

		fun createShader(type: Int, str: String): Int {
			val shaderId = gl.glCreateShader(type)
			gl.glShaderSource(shaderId, str)
			gl.glCompileShader(shaderId)

			val out = IntArray(1)
			gl.glGetShaderiv(shaderId, GL.GL_COMPILE_STATUS, out, 0)
			if (out[0] != GL.GL_TRUE) {
				throw RuntimeException("Error Compiling Shader : " + gl.glGetShaderInfoLog(shaderId) + "\n" + str)
			}
			return shaderId
		}

		fun use() {
			gl.glUseProgram(id)
		}

		fun unuse() {
			gl.glUseProgram(0)
		}

		override fun close() {
			gl.glDeleteShader(fragmentShaderId)
			gl.glDeleteShader(vertexShaderId)
			gl.glDeleteProgram(id)
		}
	}

	inner class AndroidBuffer(kind: Buffer.Kind) : Buffer(kind) {
		private var id = -1
		val glKind = if (kind == Buffer.Kind.INDEX) GL.GL_ELEMENT_ARRAY_BUFFER else GL.GL_ARRAY_BUFFER

		override fun afterSetMem() {
		}

		override fun close() {
			val deleteId = id
			gl.glDeleteBuffers(1, intArrayOf(deleteId), 0)
			id = -1
		}

		fun getGlId(): Int {
			if (id < 0) {
				val out = IntArray(1)
				gl.glGenBuffers(1, out, 0)
				id = out[0]
			}
			if (dirty) {
				_bind(id)
				if (mem != null) {
					val bb = mem!!.byteBufferOrNull
					if (bb != null) {
						val pos = bb.position()
						bb.position(memOffset)
						//println("Setting buffer($kind): ${mem.byteBufferOrNull}")
						gl.glBufferData(glKind, memLength, bb, GL.GL_STATIC_DRAW)
						bb.position(pos)
					}
				}
			}
			return id
		}

		fun _bind(id: Int) {
			gl.glBindBuffer(glKind, id)
		}

		fun bind() {
			_bind(getGlId())
		}
	}

	inner class AndroidTexture : Texture() {
		val texIds = IntArray(1)

		init {
			gl.glGenTextures(1, texIds, 0)
		}

		val tex = texIds[0]

		override fun createMipmaps(): Boolean {
			bind()
			setFilter(true)
			setWrapST()
			//glm["generateMipmap"](gl["TEXTURE_2D"])
			return false
		}

		fun uploadBuffer(data: FastMemory, width: Int, height: Int, rgba: Boolean) {
			val type = if (rgba) GL.GL_RGBA else GL.GL_LUMINANCE
			bind()
			gl.glTexImage2D(GL.GL_TEXTURE_2D, 0, type, width, height, 0, type, GL.GL_UNSIGNED_BYTE, data.byteBufferOrNull)
		}

		override fun uploadBuffer(data: ByteBuffer, width: Int, height: Int, kind: Kind) {
			uploadBuffer(FastMemory.wrap(data), width, height, kind == Kind.RGBA)
		}

		override fun uploadBitmap32(bmp: Bitmap32) {
			val mem = FastMemory.alloc(bmp.area * 4)
			mem.setArrayInt32(0, bmp.data, 0, bmp.area)
			uploadBuffer(mem, bmp.width, bmp.height, true)
		}

		override fun uploadBitmap8(bmp: Bitmap8) {
			val mem = FastMemory.alloc(bmp.area)
			mem.setArrayInt8(0, bmp.data, 0, bmp.area)
			uploadBuffer(mem, bmp.width, bmp.height, false)
		}

		fun bind(): Unit = run { gl.glBindTexture(GL.GL_TEXTURE_2D, tex) }
		fun unbind(): Unit = run { gl.glBindTexture(GL.GL_TEXTURE_2D, 0) }

		override fun close(): Unit = run { gl.glDeleteTextures(1, texIds, 0) }

		fun setFilter(linear: Boolean) {
			val minFilter = if (this.mipmaps) {
				if (linear) GL.GL_LINEAR_MIPMAP_NEAREST else GL.GL_NEAREST_MIPMAP_NEAREST
			} else {
				if (linear) GL.GL_LINEAR else GL.GL_NEAREST
			}
			val magFilter = if (linear) GL.GL_LINEAR else GL.GL_NEAREST

			setWrapST()
			setMinMag(minFilter, magFilter)
		}

		private fun setWrapST() {
			gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
			gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
		}

		private fun setMinMag(min: Int, mag: Int) {
			gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, min)
			gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, mag)
		}
	}
}
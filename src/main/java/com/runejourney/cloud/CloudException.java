package com.runejourney.cloud;

import java.io.IOException;
import lombok.Getter;

@Getter
public class CloudException extends IOException
{
	private final int status;
	private final String code;

	public CloudException(int status, String code, String message)
	{
		super(message != null ? message : "HTTP " + status);
		this.status = status;
		this.code = code;
	}

	public boolean is(String code)
	{
		return code.equals(this.code);
	}

	public boolean isKeyRejected()
	{
		return status == 401;
	}

	static Throwable cause(Throwable t)
	{
		while ((t instanceof java.util.concurrent.CompletionException || t instanceof java.util.concurrent.ExecutionException)
			&& t.getCause() != null)
		{
			t = t.getCause();
		}
		return t;
	}
}
